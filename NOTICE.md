# Third-party components

OpenFilePower uses AndroidX, Jetpack Compose, Kotlin, Kotlin Coroutines, Coil and OkHttp (Apache License 2.0), and jcifs-ng (LGPL 2.1).

jcifs-ng source and license: https://github.com/AgNO3/jcifs-ng

Additional components:

- JSch (mwiede fork) – SFTP – BSD-style license: https://github.com/mwiede/jsch
- Apache Commons Net – FTP/FTPS – Apache License 2.0
- Apache Commons Compress and XZ for Java – 7z, TAR, GZ, BZ2, XZ – Apache License 2.0 / public domain
- zip4j – password-protected ZIP (AES) – Apache License 2.0
- junrar – RAR extraction – UnRAR license (extraction only; the code may not be used to re-create the RAR compression algorithm): https://github.com/junrar/junrar
- AndroidX Biometric and Fragment – Apache License 2.0

The source build in this repository permits rebuilding the application with a modified compatible jcifs-ng library. No obfuscation is enabled. This application does not add restrictions on reverse engineering for debugging modifications to LGPL components. Transitive dependencies retain their original licenses; dependency metadata is available via Gradle.
