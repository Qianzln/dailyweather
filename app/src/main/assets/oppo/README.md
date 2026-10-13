# OPPO Weather 16.46.2 recovered scene resources

This resource pack was recovered offline from the user-supplied
`com.coloros.weather2` APK version 16.46.2, specifically `assets/weather.coz2`.
It retains the source material parameters, texture channel values, scene
transforms, lights, animation keyframes and original GLSL sources.

`pack.json` is the compact runtime scene graph: 62 selectable weather/time
presets, the empty preset and the shared MainScene. It omits duplicate editor
objects and resolved raw copies while retaining all renderer/controller inputs.
There are 89 texture references backed by 44 unique decoded PNG files,
32 rendering shaders and 95 original compute shaders.

`provenance.json` records the original archive/scene hashes and the SHA-256
of each exported file. `scripts/export_oppo_weather_assets.py` reproduces the
asset pack from the documented offline extraction directory.

Cloud RGBA files contain normal-map RGB with opacity in alpha. They must be
decoded without resizing, color correction, vertical flip or premultiplication.
Texture wrap mode is part of the GPU cache key; repeated noise textures and
clamped clouds can reference the same underlying image.

The app does not load the proprietary COE native library. SkyPulse's GLES
renderer ports the readable controllers and compute behavior. Device/GPU
floating-point random behavior and the native runtime have not been compared
frame-for-frame, so pixel-for-pixel identity is not claimed.

Rain hitting the glass and foreground splashes continue to use SkyPulse's
existing effects, as requested by the user.
