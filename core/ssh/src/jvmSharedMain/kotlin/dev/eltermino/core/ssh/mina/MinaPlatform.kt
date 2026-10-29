package dev.eltermino.core.ssh.mina

import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

object MinaPlatform {
    /** Call once, before the first connection (see docs/decisions/0001-ssh-library.md). */
    fun setUpAndroid(homeDir: File) {
        // Android ships a trimmed "BC" provider; MINA needs the full one.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        OsUtils.setAndroid(true)
        PathUtils.setUserHomeFolderResolver { homeDir.toPath() }
    }
}
