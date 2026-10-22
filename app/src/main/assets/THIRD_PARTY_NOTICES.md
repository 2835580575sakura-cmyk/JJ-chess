# Third-party notices

Application source and the modified native bridge are distributed under GNU GPLv3 (see LICENSE). Copyright 2026 JJ-chess contributors. The matching source archive contains all application modules, modified native integration, fixed Pikafish source, and build scripts.

- Pikafish / Stockfish: upstream tag Pikafish-2026-09-06, commit 4c17cee11f888ae1d48a9494f2e2239f019f0a1f, GPLv3. Authors and original license: third_party/pikafish/AUTHORS and Copying.txt. Native build changes live in engine/src/main/cpp; upstream source is unmodified.
- **pikafish.nnue is separately licensed**, NOT relicensed under GPLv3. Original terms in third_party/licenses/NNUE-License.md: no commercial use without permission; use is subject to those terms. This delivery is for the requested personal human-versus-computer practice. Obtain the author's permission before commercial use. Source and release checksums: third_party/pikafish-manifest.json.
- Kotlin standard library and kotlinx.coroutines / kotlinx.serialization: JetBrains, Apache-2.0. https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines, https://github.com/Kotlin/kotlinx.serialization
- Android NDK libc++: LLVM Project, Apache-2.0 with LLVM exceptions; see app/src/main/assets/licenses/LLVM-LICENSE.txt.
- JUnit, Robolectric, AndroidX test libraries are development/test dependencies; not part of the application runtime.
- JJ board crops are derived from the reference video supplied for this task, used only as compatibility fixtures. JJ branding, artwork and application belong to their respective owners. The full video, avatars and account UI are excluded from the source and APK.
