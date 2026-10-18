# Native interpolation source lock

Only the RIFE v4 forward path and its custom Warp are adapted here. This directory contains no
model weights, CLI, image codec, filesystem model loader or third-party downloader.

- RIFE upstream: https://github.com/nihui/rife-ncnn-vulkan
- Source/tag: `a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7` / `20221029`, MIT, retained in `upstream/LICENSE`.
- `rife_warp.cpp` derives from `src/warp.cpp`; the two `.comp` files are unchanged upstream shaders.
- `frame_engine.cpp` follows the v4 no-TTA flow in `src/rife.cpp`: RGB / 255, zero padding to 32,
  inputs `in0/in1/in2`, output `out0`, crop, round and clamp to RGB8.
- ncnn is supplied by the parent target: Tencent/ncnn `e54f7b1f88434e1d844ea0551b880a1cfb079ce1`
  / `20260526`, BSD-3-Clause and its bundled notices.
- Vulkan shader compiler closure: nihui/glslang `fe88f421038e1bb0a25cd5c1b2dfe505db82d08f`.

Local adaptations use a private namespace, check shapes/statuses and finite output, bound memory
reads and image capacities, support CPU-only builds, and avoid global GPU destruction. CPU Warp
clamps coordinates before float-to-int conversion. TTA, UHD and older model variants are omitted.
The generic ncnn Extractor handles transfers for Vulkan; this is not a zero-copy Surface renderer.
Vulkan uses float32 pack1/pack4. The obsolete upstream `use_shader_pack8` option is absent from
the pinned ncnn API; unverified pack8/fp16 paths are deliberately not enabled.

Callers authenticate the approved model bytes before `load`. Model buffers are borrowed only for
that call and are not retained. Failed model replacement disables the previous model. A single
worker owns an engine; concurrent work returns `busy`. Destruction requires callers to have stopped.
Only complete successful RGB output is committed, including when input/output buffers alias.

Converted v4.6 weights remain research-only. Their presence in the upstream repository is not
treated here as proof of a separate model redistribution grant. They must not enter APK assets.
