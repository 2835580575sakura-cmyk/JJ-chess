# 复核和修复结果

## 0.1.1 识别恢复复核

针对静止画面无法识别的修复进行独立只读复核。范围包括定位、金色底色判断、显式标准开局适配、模型私有存储、暂停与新鲜画面门控、升级校准失效，以及界面恢复入口。

发现两项需要修复的界面问题，已修改并重新复核：

- 适配预览外层滚动容器会抢走纵向角点拖动。CalibrationView 在触摸开始时占有手势，在抬起或取消时释放；新增回归验证坐标与手势归属。
- 关闭适配对话框后后台操作仍可能继续。对话框现在取消自身适配任务及等待中的协调器操作，并在应用模型和保存前核对任务、对话框及会话状态；协调器也检查取消状态。

复核结论：没有待处理的重要问题。独立复核未执行 Gradle；作者执行完整套件与 APK 检查，结果见 [验证记录](validation-results.md)。用户手机上的新版识别尚未验证。

## 0.1.0 历史记录

下方原始报告针对 7441158，记录了修复前的发现。作者随后完成一次修复；这不是第二次独立复核。全部 9 项重要问题已由失败→通过的回归测试及完整套件验证。3 项小项暂缓。

| 发现 | 修复验证 |
|---|---|
| I1 | resumeCannotSearchWithoutReconfirmingHistory |
| I2 | manualCorrectionSurvivesUnchangedRawRecognition |
| I3 | unreadableBoardAlsoRemovesMoveText |
| I4 | samplingSuppressionSurvivesNewResultsAndStaleCompletion + OverlaySamplingIntegrationTest |
| I5 | geometryInvalidationCannotBeUndoneByContinue |
| I6 | EngineCommandIngressTest |
| I7 | stopInterruptsValidationBeforeItsBarrierIsReleased |
| I8 | test_jni_faults (array/string/global/class/UTF fault injection) + missingFinalCallbackDoesNotLeaveAnalysisRunningForever |
| I9 | typed bad_alloc + nativeAllocationOrUnexplainedExitOffersOnlyManualConservativeRetry |

最终：JVM/Robolectric 88/88、实际宿主 Pikafish 10/10、生产 JNI 源码配测试 shim 的分配故障用例 8/8。Android 设备测试未运行。

---

# Fresh whole-branch review

Range: `c61c41a..7441158efa57c99a67f74dd2e431d948e4974a92`. Review performed against the review package, approved design and implementation plan. Read-only review of product code; this report is the only repository file written. No further agents were used.

Verdict: changes required. No Critical finding established. The following Important findings concern concrete behaviors not covered by the passing tests. Locations below are relative to the repository root and refer to the reviewed head.

## Important

### I1. Resume silently reuses history and turn after continuity was lost

Location: `app/src/main/kotlin/dev/sakura/xiangqi/app/SessionCoordinator.kt:134`, also lines 71–75; `core/src/main/kotlin/dev/sakura/xiangqi/core/Tracking.kt:23`.

Trigger: confirm a position, pause or open the assistant (which pauses capture sampling), change/restart/undo the JJ game during that gap, then resume with a board equal to the last confirmed board or a legal single-step successor. `resume()` resets the phase to WAITING_BOARD but retains the tracker and legal list. The next matching stable observation goes straight through Unchanged to beginSearch, or appends a move using the old history. The same happens when a previously NEEDS_SYNC state is overwritten by resume. A repeated board at two historical nodes is especially concrete: play the reversible knight cycle back to the initial layout, pause, restart JJ, resume; the four old moves are supplied to the new game's search without confirmation.

Impact: recommendations use unconfirmed turn/history after a gap, contrary to Review Focus 5; repetition analysis and potentially turn are wrong. Fresh pixels establish board identity, not game identity.

Fix direction: preserve synchronization uncertainty across pause/resume and require explicit reconfirmation after lost continuity; distinguish returning from the deliberate board-confirmation flow so that confirming a board does not immediately require confirmation again.

Regression: advance through a reversible cycle, pause, present the same board, resume, and assert no search until the user confirms the history/turn. Also pause/resume an existing NEEDS_SYNC state and assert it cannot become searchable merely because the old board reappears.

### I2. Correcting a consistently misrecognized piece cannot produce a recommendation

Location: `app/src/main/kotlin/dev/sakura/xiangqi/app/SessionCoordinator.kt:57`, lines 71–75 and 86–89; `app/src/main/kotlin/dev/sakura/xiangqi/app/MainActivity.kt:82`.

Trigger: the recognizer consistently returns board A with one wrong piece; the user edits it to the actual legal board B. Opening the activity pauses the coordinator, so confirm validates B but deliberately does not search. Returning to JJ resumes recognition; the unchanged physical screen is still recognized as A. A differs from tracker B, so it immediately invalidates and then becomes NEEDS_SYNC instead of searching B. Repeating the manual correction repeats this cycle.

Impact: the advertised manual correction flow cannot recover from the very recognition error it is meant to address. This is not a request to tolerate arbitrary future changes: the unchanged raw observation must be distinguished from a new board.

Fix direction: retain an explicitly confirmed correction against its raw observation baseline, allow analysis of the validated corrected position, and require rechecking when that baseline changes or becomes uncertain. Do not silently fabricate future moves from corrected pixels.

Regression: stable erroneous A -> pause -> edit/validate B -> return to identical raw A; assert a search of B is possible and its result remains valid. Change the raw board and assert the corrected recommendation clears and synchronization is required.

### I3. Invalidating an arrow leaves its obsolete move text visible

Location: `app/src/main/kotlin/dev/sakura/xiangqi/app/SessionCoordinator.kt:41`, lines 56–62; `app/src/main/kotlin/dev/sakura/xiangqi/app/OverlayController.kt:65`.

Trigger: obtain a READY recommendation, then start a move/animation or switch away from the board. processFrame calls invalidate, but invalidate only clears key/info and the arrow. OverlayController.clear does not clear the TextView, and the coordinator retains READY and the old message until a later stable observation changes it. If recognition stays unavailable, the old move text remains indefinitely.

Impact: the user can still act on an obsolete Chinese move recommendation even though its arrow was correctly removed. A blank/unsupported board also provides no updated explanation.

Fix direction: invalidate the textual recommendation and READY state together with the arrow; show an explicit waiting/unreadable-board status while retaining the last confirmed position only for recovery.

Regression: display a valid best move, supply an occluded/null or motion-heavy frame without subsequent stable recovery, and assert neither arrow nor move text remains visible and the state no longer presents that move as READY.

### I4. Engine callbacks can redraw the arrow inside the hidden sampling interval

Location: `app/src/main/kotlin/dev/sakura/xiangqi/app/OverlayController.kt:61`, lines 66–67; `app/src/main/kotlin/dev/sakura/xiangqi/app/OverlaySamplingGate.kt:13`; `app/src/main/kotlin/dev/sakura/xiangqi/app/SessionCoordinator.kt:121`.

Trigger: the sampling gate hides the arrow and records hiddenAt. Before the two subsequent frames arrive, a current-key BestMove calls show, which unconditionally sets the view VISIBLE. The gate still accepts the second frame as clean. restore can likewise make an arrow visible during another hide cycle because the overlay stores no sampling suppression state.

Impact: full-screen capture includes the assistant's own arrow, potentially invalidating recognition and causing repeated cancellations/restarts. Four-version result validation does not prevent this because the callback can be perfectly current.

Fix direction: make suppression explicit in the overlay; show/restore may update the stored result but must not draw while the sampling interval is active. Release suppression only through the matching sample/generation completion path, then restore a still-valid result.

Regression: hide for sampling -> inject current BestMove between the first and second fresh frames -> verify the arrow remains hidden through sampling, and becomes visible only after the accepted sample has been processed. Also exercise overlapping sample generations/late restores.

### I5. Resize/configuration pause can be undone without rebuilding coordinates

Location: `app/src/main/kotlin/dev/sakura/xiangqi/app/AssistantRuntime.kt:47`, lines 57–58 and 63; `app/src/main/kotlin/dev/sakura/xiangqi/app/ProjectionController.kt:28`.

Trigger: capture content resizes or display configuration changes. Runtime sets only userPaused and displays a request to restart; it retains the coordinator calibration/transform and the original VirtualDisplay/ImageReader surface. Clicking pause/continue clears userPaused and resumes using those old coordinates. Nothing enforces the displayed restart requirement.

Impact: the coordinate-invalid state is reversible through the ordinary continue control, allowing results with the old transform if recognition succeeds. This violates Review Focus 1 even though most mismatched frames may instead fail recognition.

Fix direction: latch capture/coordinate invalidation independently from user pause, clear the mapping, and require rebuilding the capture session/calibration (or implement a verified resize path) before accepting frames or showing any recommendation.

Regression: establish a visible result, emit a changed-size/configuration event, press continue repeatedly and inject otherwise recognizable same-board frames; assert no search or display until a new valid mapping/session is established.

### I6. Binder service dispatch can acknowledge cancellation before the canceled search is enqueued

Location: `engine/src/main/kotlin/dev/sakura/xiangqi/engine/EngineService.kt:48`, lines 53–55; `engine/src/main/kotlin/dev/sakura/xiangqi/engine/EngineActor.kt:25`.

Trigger: receive analyze(id), then cancel(id). Each Binder method launches an independent Dispatchers.IO coroutine. If the analyze coroutine has not reached actor.submit when the cancel coroutine executes, actor.cancel sees no active request and queues a no-op. The service acknowledges cancellation, then the delayed analyze coroutine queues and starts the supposedly canceled search. The actor serializes commands only after this reordering has already happened.

Impact: pause/cancel can leave a full-budget native search running after acknowledgement. With subsequent validate/analyze requests, an old submit can also arrive after new work and stop that new work. Result keys prevent old arrows but do not fulfill the stop-and-await/resource contract.

Fix direction: serialize ingress in request order or keep canceled request IDs/generations so a delayed submit cannot start after cancellation acknowledgement. Preserve immediate native stop signaling for an already-running search.

Regression: deterministically hold the service's analyze dispatch before actor.submit, deliver cancel and await its acknowledgement, release analyze, and assert native.start is never called for that ID. Cover a new search arriving after the cancel as well. Direct EngineActor ordering tests do not cover this boundary.

### I7. Explicit stop waits behind long engine initialization/validation

Location: `app/src/main/kotlin/dev/sakura/xiangqi/app/SessionCoordinator.kt:37`, lines 84 and 136–138; `app/src/main/kotlin/dev/sakura/xiangqi/app/AssistantRuntime.kt:61`; `engine/src/main/kotlin/dev/sakura/xiangqi/engine/BinderEngineGateway.kt:69`.

Trigger: on first confirmation, gateway.validate waits for NNUE installation/native initialization while operate holds the coordinator mutex. The user stops, or projection is revoked, during that wait. coordinator.stop is queued behind the same mutex; runtime waits for it before invoking the capture stop request. Initialization can occupy this path until the 30-second service watchdog/35-second gateway timeout (or validation until its timeout).

Impact: stopping may leave the recording foreground service and expensive initialization alive for many seconds after the user stopped. The overlay is removed promptly and sampling is disabled, but capture/native cleanup is delayed. I did not establish a permanent resource leak on this path; the demonstrated problem is stop latency and inability to interrupt initialization.

Fix direction: make stop invalidate the session and stop capture promptly, independently of the long operation's mutex; cancel/close the in-flight initialization safely and prevent any late completion from publishing a position or starting work.

Regression: block gateway initialization/validate, request runtime stop, and assert capture release and session invalidation occur before releasing that barrier. Release a late success and assert it cannot restart anything; cleanup remains idempotent.

### I8. JNI allocation failures are used as valid references before returning

Location: `engine/src/main/cpp/jni_bridge.cpp:32`, lines 33–34 and 43–45 (also emit at lines 15–20).

Trigger: NewObjectArray/NewStringUTF fails during legal-result construction, or NewGlobalRef fails while starting a search. The bridge continues calling SetObjectArrayElement on a null array or starts the engine with a null callback and a pending Java exception. emit similarly obtains a class/method without handling a failed/null class first. C++ catch does not catch a pending Java OOME. The string helper can also leak GetStringUTFChars if constructing std::string throws.

Impact: a recoverable allocation failure becomes undefined/invalid JNI use or an engine-process abort instead of a reported failure. This is especially relevant to a memory-intensive native engine. Isolation protects the app process but does not make the JNI path correct.

Fix direction: check every JNI allocation/lookup and ExceptionCheck before further JNI work, preserve the original exception, unwind local/global refs with RAII, and never start native search unless the callback global reference exists. Do not overwrite a pending OOME in fail().

Regression: fault-inject failed array/string/global-reference allocations in a JNI shim or Android test; assert no subsequent invalid JNI call, no search with a null callback, and proper cleanup/error propagation. Host tests of engine_bridge do not exercise JNI.

### I9. Actual native allocation failure does not activate the promised lower-memory retry

Location: `app/src/main/kotlin/dev/sakura/xiangqi/app/AssistantRuntime.kt:52`; `engine/src/main/kotlin/dev/sakura/xiangqi/engine/BinderEngineGateway.kt:53`; `engine/src/main/cpp/jni_bridge.cpp:27`.

Trigger: native allocation fails without an Android onTrimMemory/onLowMemory callback. A caught std::bad_alloc becomes a generic English IllegalStateException; the pinned engine's transposition-table allocation failure exits the engine process directly (`third_party/pikafish/src/tt.cpp:194`). The gateway reports generic disconnection. Runtime invokes memoryRetry only if the message contains the Chinese text 内存, so these real failures leave the saved 4-thread/256-MiB options unchanged.

Impact: the user is told to retry but repeats the same memory-demanding configuration; the documented 2-thread/128-MiB fallback is only implemented for the callback/string-specific cases. There is no infinite automatic restart, but manual retries can reproduce the failure indefinitely.

Fix direction: use a structured allocation/resource failure event where available and provide a bounded/conservative lower-memory retry path for native initialization death rather than relying on localized substring matching. Avoid claiming every unrelated crash was definitively OOM.

Regression: simulate native bad_alloc and native process death during allocation with no trim-memory callback; assert the next manual retry can use the lower-memory configuration and the UI explains the retry choice without automatically looping.

## Minor / deferred

- `core/src/main/kotlin/dev/sakura/xiangqi/core/MoveNotation.kt:16`: disambiguation is per-file only. If multiple files contain stacked pawns, 前兵/后兵 can be ambiguous across files. Use the documented coordinate fallback for positions the formatter cannot uniquely name. Regression: two files each containing two same-side pawns. This does not invalidate the arrow and is suitable for a later polish pass.
- `app/src/main/kotlin/dev/sakura/xiangqi/app/OverlayController.kt:56`: when there is no room outside the board the whole panel becomes GONE; no collapsed handle is offered, despite the design saying fold/collapse. The notification remains a route back. Defer pending real-device layout evidence rather than inventing a layout now.
- Many Kotlin files put entire control flows on one line. This makes lifecycle changes harder to audit, but formatting alone is not a reason to delay delivery after the behavioral findings are addressed.

## Reviewed evidence and scope

Inspected the own app, core, vision and engine implementation, JNI/C++ integration, corresponding unit/replay/native/device-test code, manifest/build configuration, source packaging and APK verification scripts, delivery docs, licenses/notices and build manifest. The upstream library was consulted narrowly to verify actual set_position validation and allocation-failure behavior; it was not exhaustively audited. FEN/rule validation is not merely trusting raw unchecked text: the pinned upstream set_position validates board shape, piece limits/placement, king legality and each historical move. Source packaging excludes private keys, the raw recording and .superpowers, and the manifest identifies the reviewed commit and bundled NNUE. No additional source/privacy defect was established in these paths.

The supplied 75 JVM/Robolectric and 10 host-native passing results were examined as existing evidence, not represented as independently rerun by this reviewer. The findings above are code-path/interleaving findings, with suggested regressions; no Android execution or fault-injection was performed in this review. Existing passing pause/resume and component-level sampling tests specifically do not cover the problematic continuity/suppression boundaries.

## Declined to judge

- Actual Android/OriginOS MediaProjection behavior, frame timestamp/compositor timing, Binder/JNI execution, overlay touch-through, target-phone UI dimensions, thermal policy delivery, real memory pressure, battery consumption and 15-minute performance: no Android device execution.
- Recognition generalization beyond supplied cropped fixtures, all JJ animations/skins, unseen positions, and practical search strength/win rate: insufficient representative evidence.
- Exact equivalence of Pikafish repetition/adjudication with JJ rules, and exhaustive upstream Pikafish correctness/security: pinned vendor code is outside this integration review.
- A legal opinion on combined GPL/source and separately restricted NNUE distribution, or ownership of JJ artwork: notices and separate terms are present, but this is not legal clearance.
- Bit-identical clean-host reproducibility and independent re-verification of APK signing/ELF alignment: delivery scripts/evidence were inspected; the full build/check suite was not rerun here.

Installed code-reviewer.md template remains unavailable as already ledgered; this report uses a normal correctness/lifecycle/security/testing review rubric.
