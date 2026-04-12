package io.github.kdroidfilter.nucleus.systeminfo

import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.systeminfo.linux.LinuxSystemInfo
import io.github.kdroidfilter.nucleus.systeminfo.macos.MacOsSystemInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.ComponentInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.CpuGlobalInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.DiskInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.GpuInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.MemoryInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.MotherboardInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.NetworkInterfaceInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.OsInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.ProcessInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.ProductInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.UserInfo
import io.github.kdroidfilter.nucleus.systeminfo.windows.WindowsSystemInfo

@Suppress("TooManyFunctions")
object SystemInfo {
    private val delegate: PlatformSystemInfo? =
        when (Platform.Current) {
            Platform.Windows -> WindowsSystemInfo
            Platform.MacOS -> MacOsSystemInfo
            Platform.Linux -> LinuxSystemInfo
            else -> null
        }

    fun isAvailable(): Boolean = delegate?.isAvailable() ?: false

    fun osInfo(): OsInfo? = delegate?.osInfo()

    fun memoryInfo(): MemoryInfo? = delegate?.memoryInfo()

    fun cpuInfo(): CpuGlobalInfo? = delegate?.cpuInfo()

    fun disks(): List<DiskInfo> = delegate?.disks() ?: emptyList()

    fun components(): List<ComponentInfo> = delegate?.components() ?: emptyList()

    fun networks(): List<NetworkInterfaceInfo> = delegate?.networks() ?: emptyList()

    fun users(): List<UserInfo> = delegate?.users() ?: emptyList()

    fun motherboard(): MotherboardInfo? = delegate?.motherboard()

    fun product(): ProductInfo? = delegate?.product()

    fun processes(): List<ProcessInfo> = delegate?.processes() ?: emptyList()

    fun process(pid: Long): ProcessInfo? = delegate?.process(pid)

    fun gpus(): List<GpuInfo> = delegate?.gpus() ?: emptyList()
}
