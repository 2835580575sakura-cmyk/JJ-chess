# 实现取舍与复核记录

本轮执行期间的全部取舍及判断成本：

- Ruling: Skill helper scripts and writing-good-tests resource are unavailable through installed cloud skill provider; reproduce their ledger/brief/test logging locally — keeps durable evidence without blocking code — cost if wrong: less automatic bookkeeping.

- Task 1: Ruling: SDK commandline-tools 23 delegates to new Android CLI and fails creating bin in read-only profile; use officially checksummed 19.0 (13114758), keep SDK/ANDROID_USER_HOME in workspace — supports required platform/NDK without privileged writes — cost if wrong: older SDK CLI compatibility, verified by actual builds.

- Task 1: Ruling: host Java21 is JRE-only (no javac/JNI headers); install SHA256-verified Temurin21.0.12.1+1 under workspace, retain system CA trust, and generate local ignored debug keystore — required Android/host compilation — cost if wrong: extra disk usage and JDK distribution dependency.

- Task 6: Ruling: Robolectric4.15.1 hardcodes its download lock to Java user.home (confirmed by javap); set only the forked test JVM user.home to ignored build/robolectric-home and Maven cache to workspace — preserves global HOME and TLS verification — cost if wrong: test JVM profile differs from host, no app runtime impact.

- Task 10: Ruling: first delivery requests default-display whole-screen capture instead of optional single-app capture — matches physical screen coordinates and avoids unverified app-content letterbox offsets on OriginOS — cost if wrong: broader user-authorized capture scope and no single-app-only mode in this version; frames stay in RAM and export requires recognized board.

- Final: Ruling: installed skill provider cannot retrieve requesting-code-review/code-reviewer.md either; provide the fresh reviewer with the full spec, plan, severity rubric, Review Focus and ledger — cost if wrong: missing details from the unavailable template.

- Final: Ruling: hardware behavior declined by reviewer — retain all Android/OriginOS/touch/thermal/performance cases as unrun, do not claim device readiness — cost if wrong: device-specific defects may remain.

- Final: Ruling: recognition generalization/search strength declined — scope evidence to supplied theme/fixtures and pinned engine, no strongest-ranking or unseen-skin promise — cost if wrong: practical reliability/strength may differ.

- Final: Ruling: JJ repetition equivalence/upstream exhaustive audit declined — use pinned Pikafish validation and explicitly preserve history completeness — cost if wrong: edge adjudication may differ from JJ.

- Final: Ruling: legal opinion declined — include GPL corresponding source and separate NNUE terms for requested personal practice, make no broader legal-clearance claim — cost if wrong: broader redistribution may require additional permission.

- Final: Ruling: independent bit-identical rebuild/signature recheck declined — retain author-run APK verification and source/version manifest, no bit-identical clean-host promise — cost if wrong: independent reproduction may expose host/signing differences.

## 暂缓的小项

- Final: minor (deferred): stacked pawns on multiple files can make Chinese disambiguation ambiguous; coordinate arrow remains correct.

- Final: minor (deferred): no collapsed panel handle when there is no safe panel room; notification remains available.

- Final: minor (deferred): compact one-line Kotlin formatting reduces audit readability.
