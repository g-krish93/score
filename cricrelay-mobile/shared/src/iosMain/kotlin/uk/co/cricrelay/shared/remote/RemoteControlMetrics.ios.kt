package uk.co.cricrelay.shared.remote

import platform.Foundation.NSDate

actual fun remoteMetricsNowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
