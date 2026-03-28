package io.github.kdroidfilter.nucleus.taskbarprogress.linux

import io.github.kdroidfilter.nucleus.core.runtime.tools.LinuxDesktopFileDetector as CoreDetector

/** Delegates to [CoreDetector] in core-runtime. */
internal object LinuxDesktopFileDetector {
    val desktopFilename: String? get() = CoreDetector.desktopFilename
}
