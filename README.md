<div align="center">

<img src=".github/resources/cannoli_nobg.png" width="256px" alt="A cannoli you silly goose!">
<h3 style="font-size:35px; padding-top:0px; padding-bottom:0px; margin-bottom: 0px; margin-top: 5px;">
Cannoli
</h3>

<h4 style="font-size:18px; padding-top:0px; margin-top:0px;">A frontend with just the right amount of filling!</h4>

<video src="https://github.com/user-attachments/assets/84ef99a6-faeb-487d-93ad-6fbeaf607b75" width="600" autoplay loop muted playsinline></video>

</div>

---

# Why make another frontend?

`¯\_(ツ)_/¯`

I adore [MinUI](https://github.com/shauninman/MinUI) by [Shaun Inman](https://github.com/shauninman). No fuss, a focused
feature set, and unapologetically opinionated.

Without his masterpiece this derivative would not exist.

For that I thank him, and apologize for the bastardization that follows.

Since purchasing a Retroid Pocket Classic I've been yearning for MinUI's simplicity.

**This is my attempt to bring that experience to Android.**

---

# Design Goals

- Play Some Damn Games!
- Minimal Configuration
- Easy to add new games
- Basic features baked in
- Judicious addition of additional features

---

# Targeted Features

## Frontend
- Opinionated and Unambiguous Directory Structure
- MinUI-inspired UI
- Basic creature comforts right in the UI
  - Rename
  - Delete
  - Collections
  - Favorites
  - Platform Reordering
  - Collection Reordering
  - Search in Collection / Platform
- Box Art
- Background Wallpaper (non-dynamic)
- Accent Colors
- Associating each platform with a particular emulator

## Built-In Core Runner (experimental)
Cannoli comes with a built-in libretro core runner that is a shameless rip-off of minarch from MinUI.

Here's the plan, if you are a grizzled vet use Cannoli as a launcher.

If you are a beginner, use the built-in core runner. When you are ready for more features and complexity use the external emulators.

You don't have to choose one path over the other.

Want the simplicity of the built-in core runner but want to play games that don't have a libretro core or function better with an external emulator? That's supported.

The best news is the opinionated directory structure is compatible with both approaches, no file wrangling required.

## Playing Games With External Applications

- Launching RetroArch
- Launching Standalone Emulators

## Standard Android Launcher Fare
- Launching Android Apps and Games

---

# Features That Will Not Be Considered Nor Implemented

## In the frontend
- Art Scraper
- Game Time Tracker
- LED Control
- Menu Music (yuck, but I respect that you might like it)
- Themes
- Video Previews (yuck x2, but I don't respect that you might like them)

## In the built-in core runner
- GLSL / Slang Shaders
- Netplay
- RetroAchievements

For all of these just use RetroArch. You can choose to use RetroArch by platform or by individual game.

## But I need that feature!

If that is the case don't switch. Sorry for being dismissive!

It has become a meme in this hobby to spend all this time curating to never play anything.

(Yes I understand the irony of building a custom launcher rather than playing games. I'm a hypocrite.)

---

# The question every project is asked. How is AI used?

I've been working AI into my development workflow as of late both professionally and for fun.

There is a stigma against vibe-coded projects and rightfully so.

My personal opinion is that it is only a problem for mission-critical stuff and when it is not disclosed.

My promise to you is I will disclose how AI was used in each project.

---

## What did you do vs what did AI do?

My current tool of choice is Claude Code.

Here is what I used Claude Code for. Everything produced was reviewed.

My biggest sin is the internal libretro core runner. I designed the specs, Claude designed it, I reviewed it.

The libretro cores are doing so much of the heavy lifting that I am not too worried about this.

Now for the lesser sins!

- Help with the boring UI stuff (layout, scaling, etc.).
- Kotlin syntax help (my first time using it)
- Copy-editor for documentation
- Nano Banana made the Cannoli pixel art logo. I think it is cute.

Here is what I built:

- Project scope, architecture, and design decisions that weren't a rip-off of MinUI
- Data models (Platform, Game, Collection, LaunchTarget)
- File management (scanner, platform resolver, INI parser, atomic rename)
- Launcher integrations (RetroArch, standalone emulators, APK launching)
- Settings system (SharedPreferences repository, enums, reactive state)
- Input handling (controller routing, layout swap logic)
- Navigation structure and guard logic
- Custom ordering and persistence (platforms, collections)
- API for the Nonna's Kitchen

Documentation is written completely by me and will never be outsourced to the clankers.

Claude did help with grammar and as a sounding board to point out when I wasn't making any sense.

You can decide if these two statements are opposed or not.

I consider Claude just another tool and have been upfront about its usage out of respect to you.

You can decide how that impacts your desire to use this project.

Don't like AI? I don't care. I build this for fun, and you didn't pay shit for it. Take it for what it is.

---

# Credits and Licenses

| Name                        | Detail                       |
|-----------------------------|------------------------------|
| Shaun Inman                 | MinUI — Inspiration          |
| M+ Fonts Project            | M PLUS 1 Code — OFL          |
| Nerd Fonts                  | NerdSymbols — OFL            |
| ZXing                       | QR Code Library — Apache 2.0 |
| Atari800                    | GPLv2                        |
| Beetle NeoPop               | GPLv2                        |
| Beetle PCE FAST             | GPLv2                        |
| Beetle PC-FX                | GPLv2                        |
| Beetle VB                   | GPLv2                        |
| Beetle Wonderswan           | GPLv2                        |
| blueMSX                     | GPLv2                        |
| DOSBox-Pure                 | GPLv2                        |
| FCEUmm                      | GPLv2                        |
| FreeIntv                    | GPLv3                        |
| Gambatte                    | GPLv2                        |
| Genesis Plus GX             | Non-commercial               |
| Handy                       | Zlib                         |
| mGBA                        | MPLv2.0                      |
| Mupen64Plus-Next            | GPLv2                        |
| Nestopia                    | GPLv2                        |
| PCSX ReARMed                | GPLv2                        |
| PicoDrive                   | MAME                         |
| PokeMini                    | GPLv3                        |
| ProSystem                   | GPLv2                        |
| Snes9x                      | Non-commercial               |
| Stella                      | GPLv2                        |
| SwanStation                 | GPLv3                        |
| vecx                        | GPLv3                        |
| Virtual Jaguar              | GPLv3                        |
| lcd3x by Gigaherz           | Shader — Public domain       |
| zfast_crt_geo by Greg Hogan | Shader — GPLv2               |

---

# Spread Joy!

I've spent a lot of time building Cannoli.

If you enjoy using it and feel inclined to pay it forward, go do something nice for someone! ❤️