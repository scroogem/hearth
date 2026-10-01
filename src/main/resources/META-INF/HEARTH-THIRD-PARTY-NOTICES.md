# Third party notices

## e4steam

Copyright 2026 Kamilchik. Apache License, Version 2.0.
Source: https://github.com/Kamilhik/e4steam, revision e6e3aeda2640527ed6dbb016d7728e6ea848a4ec.

SteamNetworkingMessagesTransport.java is adapted from e4steam. Hearth removes game-server and unreliable-message support, uses Minecraft's JNA, and closes native identity buffers explicitly. The TCP frame format is compatible with e4steam v4. The Apache license is included in META-INF/licenses/e4steam-Apache-2.0.txt in the mod JAR.

## Valve Steamworks

The bundled steam_api64.dll, libsteam_api.so and libsteam_api.dylib are Valve Steamworks redistributables obtained from e4steam 0.3.2 (steamworks4j 1.10.0). They remain subject to Valve's applicable Steamworks SDK terms and are not licensed under Hearth's or e4steam's license. This private development build uses App ID 480 for testing. A public release requires the applicable Valve redistribution agreement and a production App ID.

https://partner.steamgames.com/doc/sdk/api

## Existing dependencies

JGit, JavaEWAH, and Apache MINA SSHD retain the notices and licenses included in their nested JARs. JNA is provided by Minecraft; Hearth does not bundle a separate JNA copy.
