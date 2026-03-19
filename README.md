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

My promise to you is I will disclose how AI was used in each project. You can find this detailed disclosure in [AI_DISCLOSURE.md](AI_DISCLOSURE.md)

---

# Credits and Licenses

| Name                        | What            | License        |
|-----------------------------|-----------------|----------------|
| M+ Fonts Project            | M PLUS 1 Code   | OFL            |
| Nerd Fonts                  | NerdSymbols     | OFL            |
| ZXing                       | QR Code Library | Apache 2.0     |
| Atari800                    | Core            | GPLv2          |
| Beetle NeoPop               | Core            | GPLv2          |
| Beetle PCE FAST             | Core            | GPLv2          |
| Beetle PC-FX                | Core            | GPLv2          |
| Beetle VB                   | Core            | GPLv2          |
| Beetle Wonderswan           | Core            | GPLv2          |
| blueMSX                     | Core            | GPLv2          |
| DOSBox-Pure                 | Core            | GPLv2          |
| FCEUmm                      | Core            | GPLv2          |
| FreeIntv                    | Core            | GPLv3          |
| Gambatte                    | Core            | GPLv2          |
| Genesis Plus GX             | Core            | Non-commercial |
| Handy                       | Core            | Zlib           |
| mGBA                        | Core            | MPLv2.0        |
| Mupen64Plus-Next            | Core            | GPLv2          |
| Nestopia                    | Core            | GPLv2          |
| PCSX ReARMed                | Core            | GPLv2          |
| PicoDrive                   | Core            | MAME           |
| PokeMini                    | Core            | GPLv3          |
| ProSystem                   | Core            | GPLv2          |
| Snes9x                      | Core            | Non-commercial |
| Stella                      | Core            | GPLv2          |
| SwanStation                 | Core            | GPLv3          |
| vecx                        | Core            | GPLv3          |
| Virtual Jaguar              | Core            | GPLv3          |
| lcd3x by Gigaherz           | Shader          | Public domain  |
| zfast_crt_geo by Greg Hogan | Shader          | GPLv2          |

---

# Spread Joy!

I've spent a lot of time building Cannoli.

If you enjoy using it and feel inclined to pay it forward, go do something nice for someone! ❤️