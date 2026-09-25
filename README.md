# AuraMap

I was attempting to write a minimap for my other mod, [Zephyr](https://github.com/alderjhuynh/zephyr), and I am a big fan of Xaero's minimap, so I wanted to see if there was a source available and a permissive license. However, I was disappointed to find that both  Xaero's World Map and Xaero's Minimap were not open source, and were in fact licensed ARR. Unfortunately, I understand why this is, and there's not much I can do about it.

The other open source map mods are... lackluster, and, in my opinion, just not as good as Xaero's. 

With AuraMap, I've attempted to recreate Xaero's World Map (and soon, Xaero's Minimap) at least somewhat faithfully. Of course, Xaero's is licensed ARR, so I've recreated it *solely* from my own memory and experience of playing using Xaero's. None of this code and none of the assets were ever taken, even partially, from Xaero's. This is a recreation only in spirit. 

## Disclaimers & Notes

Please keep in mind that I'm just one person, so you may encounter bugs, issues, lag, or other problems. If you do, please open a pull request or an issue and I will do my best to help resolve your problem. 

This project is, and always will be, licensed under the [WTFPL](https://www.wtfpl.net/). Any and all of its code, assets, or anything else within the repo can be used for whatever purpose you may desire. 

## Features

A lightweight, client-side world map, focused on being fast, readable, and unobtrusive.

### Available now

- **Fullscreen world map**: Drag to pan, scroll to zoom.
- **Fills in as you explore**: Chunks around you are captured in the background as you travel, so the map builds itself naturally and previously visited areas load instantly.
- **Separate map for every world and dimension**: Overworld, Nether, and End each keep their own data, and single-player worlds and servers are kept apart. Maps are saved under `aura/auramap/`
- **Fast and non-intrusive saves**: Uses a compact binary format that saves in the background without the long freeze on leaving a world.
- **Map that actually looks like terrain**: Block colors, heightmap, and an attempt at looking like vanilla maps.
### Planned

- **Minimap overlay, waypoints, and cave view**