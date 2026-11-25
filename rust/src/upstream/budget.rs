//! The caller's absolute deadline, shared by nested requests and retries.

use std::future::Future;
use std::time::Duration;

use tokio::time::Instant;

pub(crate) const HTTP_REQUEST_TIMEOUT: Duration = Duration::from_secs(20);

/// Loopback deadline for routes whose handler synchronously downloads an
/// upstream file before responding (currently the single-mode
/// `/api/v1/videos/<id>` disk-download path). The header-fetch phase still
/// respects the normal cap via `cap_timeout`; what this widens is the outer
/// race so a slow WiFi can finish transferring a multi-MB episode without
/// the adapter cutting it off at 20 s. Body size stays bounded by the
/// endpoint's own `MAX_VIDEO_DOWNLOAD_BYTES` cap.
pub(crate) const HTTP_MEDIA_DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(180);

/// Returns `HTTP_MEDIA_DOWNLOAD_TIMEOUT` for paths that download the whole
/// body before responding, else `HTTP_REQUEST_TIMEOUT`. Loopback adapter only;
/// the FRB bridge takes its deadline from the Dart caller.
pub(crate) fn loopback_dispatch_timeout(path: &str) -> Duration {
    // The handler passes the *raw* URI path, which may carry the
    // `/_session/<token>/...` capability prefix before the API path. Match on
    // the suffix instead of stripping the prefix here so the gate works for
    // both URL shapes.
    if path.ends_with("/api/v1/videos") || path.contains("/api/v1/videos/") {
        return HTTP_MEDIA_DOWNLOAD_TIMEOUT;
    }
    HTTP_REQUEST_TIMEOUT
}

tokio::task_local! {
    static DEADLINE: Instant;
}

pub(crate) async fn within<F: Future>(limit: Duration, work: F) -> Result<F::Output, ()> {
    let deadline = Instant::now() + limit;
    DEADLINE
        .scope(deadline, async {
            let result = tokio::time::timeout_at(deadline, work)
                .await
                .map_err(|_| ())?;
            // CPU work can finish after the deadline without yielding to the timer.
            if Instant::now() >= deadline {
                Err(())
            } else {
                Ok(result)
            }
        })
        .await
}

pub(crate) fn remaining() -> Option<Duration> {
    DEADLINE
        .try_with(|deadline| deadline.saturating_duration_since(Instant::now()))
        .ok()
}

pub(crate) fn cap_timeout(limit: Duration) -> Duration {
    remaining().map_or(limit, |left| left.min(limit))
}

pub(crate) async fn retry_pause(delay: Duration) -> bool {
    if remaining().is_some_and(|left| left <= delay) {
        return false;
    }
    tokio::time::sleep(delay).await;
    !remaining().is_some_and(|left| left.is_zero())
}
