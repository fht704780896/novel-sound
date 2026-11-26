import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'platform_channel.dart';

/// Network environment for the CDN download channel (drama/audio caching).
///
/// Why this exists: the cached-download path uses Dart's [HttpClient], which
/// differs from the app's other network stacks in two ways that break on
/// restrictive WiFi networks:
///
/// 1. **Proxy**: Dart never consults the WiFi HTTP proxy (no environment
///    variables on Android, no automatic system-proxy lookup), so any network
///    that requires the proxy to reach the internet gets connection failures.
///    The ExoPlayer playback path (Java) and the desktop Rust core both see
///    the proxy; this channel does not.
/// 2. **Root certificates**: Dart trusts a built-in Mozilla root list baked
///    into the VM. It ignores Android's system trust store, so a WiFi HTTPS
///    inspection gateway (private CA) — and any CDN whose chain relies on a
///    CA absent from Mozilla's list, e.g. several Chinese CAs — fails the TLS
///    handshake, while the same URL opens fine in a browser.
///
/// [DownloadNetwork.ensure] resolves both once (proxy re-queried per call so a
/// WiFi change is picked up, certificates cached for the process lifetime)
/// and [DownloadNetwork.createClient] applies them to each new client.
class DownloadNetwork {
  DownloadNetwork._();

  static const _channel = AppMethodChannel('fqapp/network_proxy');

  /// Latest WiFi proxy as `host:port`, null when the network has none.
  static String? _proxy;

  /// Android system trust store loaded into a fresh security context, or null
  /// when unavailable (non-Android, empty/missing store, load failure).
  static SecurityContext? _context;
  static Future<void>? _contextFuture;

  /// Re-reads the system proxy and waits for the one-time certificate load.
  ///
  /// Called before every transfer so a proxy change (switching WiFi) is seen
  /// by the next download without an app restart.
  static Future<void> ensure() async {
    _proxy = await _queryProxy();
    _contextFuture ??= _loadSystemContext();
    await _contextFuture;
  }

  static Future<String?> _queryProxy() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'getSystemProxy',
      );
      if (result == null) return null;
      final host = result['host'];
      final port = result['port'];
      if (host is! String || host.isEmpty) return null;
      if (port is! num || port <= 0 || port > 65535) return null;
      return '$host:$port';
    } on PlatformException {
      return null;
    } on MissingPluginException {
      // iOS/web builds (and tests) have no handler: keep the direct route.
      return null;
    }
  }

  static Future<SecurityContext?> _loadSystemContext() async {
    if (defaultTargetPlatform != TargetPlatform.android) return null;
    try {
      const store = '/system/etc/security/cacerts';
      final dir = Directory(store);
      if (!await dir.exists()) return null;
      // A fresh SecurityContext starts empty; the Android trust store is a
      // superset of Dart's built-in Mozilla list, so replacing (not merging)
      // loses nothing while adding CAs the VM does not know — including the
      // private CA of an HTTPS-inspecting WiFi gateway.
      final context = SecurityContext();
      var loaded = 0;
      final files = await dir.list().toList();
      for (final entry in files) {
        if (entry is! File) continue;
        try {
          context.setTrustedCertificates(entry.path);
          loaded++;
        } on FileSystemException {
          // A store entry the Dart parser rejects (unusual format) must not
          // sink the whole load; the remaining CAs still apply.
        } on TlsException {
          // Same as above: skip and continue.
        }
      }
      return loaded > 0 ? context : null;
    } catch (_) {
      // Any unexpected failure (permission, I/O) keeps the default context.
      return null;
    }
  }

  /// Builds an [HttpClient] with the resolved proxy and trust store applied.
  ///
  /// Synchronous so it satisfies the downloaders' existing
  /// `HttpClient Function()` factories; call [ensure] first (transferDownload
  /// does) or the first clients run with direct routing only.
  static HttpClient createClient() {
    // A null context falls back to Dart's built-in root list; a loaded system
    // context replaces it (see [_loadSystemContext] for why that is safe).
    final client = HttpClient(context: _context)
      ..autoUncompress = false;
    final proxy = _proxy;
    if (proxy != null) {
      // WiFi proxies are plain HTTP proxies; HTTPS goes through CONNECT.
      client.findProxy = (uri) => 'PROXY $proxy';
    }
    return client;
  }
}

/// Constructs a download [HttpClient] with system proxy and trust store.
HttpClient createDownloadHttpClient() => DownloadNetwork.createClient();
