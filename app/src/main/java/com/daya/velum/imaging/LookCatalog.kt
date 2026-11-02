package com.daya.velum.imaging

import com.daya.velum.imaging.LookCategory.*

/**
 * Built-in looks. All are original parametric grades defined here and tuned on real S24 Ultra captures; none
 * reproduces another manufacturer's preset file or colour science.
 *
 * The BRAND looks are modelled on how each camera maker publicly describes its own rendering (Leica Looks page,
 * Xiaomi/Leica Authentic and Vibrant, vivo ZEISS colour modes, Hasselblad Natural Colour Solution and OPPO's
 * Hasselblad Master filters, Sony Creative Look help guide and the S-Cinetone white paper). They reproduce the
 * described character on this phone's image; they are not the makers' own processing, which runs inside their ISPs.
 */
object LookCatalog {
    val NEUTRAL = Look("neutral", "Neutral", NATURAL, "No grade. Exactly what the camera produced.")

    private fun leica(id: String, name: String, desc: String, p: LookParams) = Look(id, name, BRAND, desc, p, brand = "Rangefinder")
    private fun zeiss(id: String, name: String, desc: String, p: LookParams) = Look(id, name, BRAND, desc, p, brand = "Optic")
    private fun hassel(id: String, name: String, desc: String, p: LookParams) = Look(id, name, BRAND, desc, p, brand = "Studio")
    private fun sony(id: String, name: String, desc: String, p: LookParams) = Look(id, name, BRAND, desc, p, brand = "Cine")

    val BRANDS = listOf("Rangefinder", "Optic", "Studio", "Cine")

    /** Shown under each signature group: what it is inspired by. Velum is not affiliated with these makers. */
    val BRAND_NOTES = mapOf(
        "Rangefinder" to "Inspired by Leica's published descriptions of its looks.",
        "Optic" to "Inspired by vivo's published descriptions of its ZEISS colour modes.",
        "Studio" to "Inspired by OnePlus and OPPO's published descriptions of Hasselblad colour and filters, and by panoramic film.",
        "Cine" to "Inspired by Sony's published descriptions of S-Cinetone and its Creative Looks.",
    )

    /** One-line form of [BRAND_NOTES] for the camera screen, where the text sits over the live image. */
    val BRAND_MAKERS = mapOf("Rangefinder" to "Leica", "Optic" to "ZEISS", "Studio" to "Hasselblad", "Cine" to "Sony")

    private val brandLooks = listOf(
        // ---- Leica
        leica("authentic", "Authentic",
            "Classic rendering: deeper, well-drawn shadows, realistic local contrast, natural colour that is " +
                "never pushed, olive-leaning greens, deep blue skies and the lens's own corner fall-off.",
            LookParams(exposure = -0.28f, contrast = 0.48f, saturation = -0.04f, warmth = 0.04f, highlightRoll = 0.12f,
                vignette = 0.38f, sharpness = 0.2f, skinProtect = 0.45f, shadows = -0.12f, highlights = -0.05f,
                bands = HueBands.of(Band.RED to Adj(s = 0.06f, l = -0.08f), Band.YELLOW to Adj(h = -4f, s = -0.12f),
                    Band.GREEN to Adj(h = -10f, s = -0.22f, l = -0.12f), Band.AQUA to Adj(s = -0.1f),
                    Band.BLUE to Adj(h = -4f, s = 0.04f, l = -0.15f)))),
        leica("vibrant", "Vibrant",
            "Brighter, livelier rendering: lifted exposure, richer colour led by vibrance, open shadows, clear blue skies, no vignette.",
            LookParams(exposure = 0.12f, vibrance = 0.5f, saturation = 0.08f, contrast = 0.1f, highlightRoll = 0.35f,
                sharpness = 0.1f, skinProtect = 0.55f,
                bands = HueBands.of(Band.RED to Adj(s = 0.06f), Band.GREEN to Adj(h = -4f, s = 0.06f), Band.BLUE to Adj(s = 0.12f, l = -0.05f)))),
        leica("leica_mono", "Monochrome",
            "Natural black-and-white: full tonal range, gentle shoulder, fine detail, soft corner fall-off.",
            LookParams(mono = 1f, contrast = 0.3f, highlightRoll = 0.35f, shadows = -0.05f, monoRed = 0.38f, monoGreen = 0.5f, monoBlue = 0.12f,
                sharpness = 0.2f, vignette = 0.2f)),
        leica("leica_mono_hc", "Monochrome HC",
            "High-contrast black-and-white: inky shadows, bright whites, darker skies from a warm channel mix.",
            LookParams(mono = 1f, contrast = 0.85f, shadows = -0.25f, highlights = 0.1f, exposure = -0.1f,
                monoRed = 0.5f, monoGreen = 0.42f, monoBlue = 0.08f, vignette = 0.3f, grain = 0.08f, sharpness = 0.3f)),
        leica("leica_classic", "Classic",
            "An analogue, cinematic style with high contrast, soft saturation and warm, slightly washed-out colour.",
            LookParams(contrast = 0.4f, saturation = -0.25f, warmth = 0.14f, fade = 0.12f, highlightHue = 40f, highlightAmt = 0.25f,
                highlightRoll = 0.35f, grain = 0.1f, skinProtect = 0.3f,
                bands = HueBands.of(Band.GREEN to Adj(h = -8f, s = -0.15f), Band.BLUE to Adj(s = -0.1f)))),
        leica("leica_contemporary", "Contemporary",
            "Modern and light, with bright shadows, natural colour and a subtle reddish tint; soft, natural skin.",
            LookParams(shadows = 0.35f, contrast = 0.08f, warmth = 0.04f, tint = 0.04f, highlightHue = 8f, highlightAmt = 0.12f,
                highlightRoll = 0.45f, skinProtect = 0.5f, skinSat = -0.05f, sharpness = -0.05f,
                bands = HueBands.of(Band.RED to Adj(s = 0.05f)))),
        leica("leica_teal", "Teal",
            "A cool, cinematic mood built on refined blue-green tones; made for cities and landscapes.",
            LookParams(warmth = -0.12f, tint = -0.03f, shadowHue = 186f, shadowAmt = 0.6f, contrast = 0.2f, saturation = -0.08f, skinProtect = 0.6f,
                bands = HueBands.of(Band.BLUE to Adj(h = -12f, s = 0.05f), Band.AQUA to Adj(s = 0.1f),
                    Band.GREEN to Adj(h = 12f, s = -0.2f), Band.ORANGE to Adj(s = -0.05f)))),
        leica("leica_chrome", "Chrome",
            "A muted palette that brings out subtle differences in tone; quiet and elegant.",
            LookParams(saturation = -0.32f, contrast = 0.3f, shadows = -0.05f, highlights = -0.08f, highlightRoll = 0.3f, sharpness = 0.15f,
                skinProtect = 0.3f, bands = HueBands.of(Band.RED to Adj(s = 0.08f, l = -0.05f)))),
        leica("leica_bleach", "Bleach",
            "Muted saturation and cool blue hues for an understated, atmospheric picture.",
            LookParams(saturation = -0.55f, contrast = 0.38f, warmth = -0.07f, shadowHue = 205f, shadowAmt = 0.12f, highlightRoll = 0.3f, skinProtect = 0.3f, bands = HueBands.of(Band.RED to Adj(h = 6f), Band.MAGENTA to Adj(h = 10f)),
                shadows = -0.05f, sharpness = 0.15f)),
        leica("leica_selenium", "Selenium",
            "Black-and-white with cool, selenium-toned shadows and soft contrast.",
            LookParams(mono = 1f, contrast = 0.05f, highlightRoll = 0.4f, shadowHue = 222f, shadowAmt = 0.5f, highlightHue = 215f, highlightAmt = 0.08f,
                monoRed = 0.35f, monoGreen = 0.5f, monoBlue = 0.15f)),
        leica("leica_blue", "Blue",
            "The deep Prussian blue of a cyanotype print, with paper-white highlights.",
            LookParams(mono = 1f, contrast = 0.15f, shadowHue = 214f, shadowAmt = 2.2f, highlightHue = 205f, highlightAmt = 0.35f, fade = 0.04f)),
        // ---- ZEISS (vivo)
        zeiss("zeiss_natural", "Faithful",
            "Accuracy first: faithful hues, neutral white balance, restrained saturation " +
                "and clean micro-contrast. Greens are kept true rather than pushed yellow.",
            LookParams(saturation = -0.06f, contrast = 0.12f, highlightRoll = 0.4f, skinProtect = 0.7f, sharpness = 0.15f, shadows = -0.03f,
                bands = HueBands.of(Band.GREEN to Adj(h = 4f, s = -0.06f), Band.YELLOW to Adj(s = -0.05f)))),
        zeiss("zeiss_vivid", "Lively",
            "Bright faces, soft contrast, open shadows and lively skies and foliage.",
            LookParams(exposure = 0.12f, shadows = 0.15f, vibrance = 0.4f, saturation = 0.06f, highlightRoll = 0.4f, skinBright = 0.06f,
                skinProtect = 0.5f, sharpness = 0.15f,
                bands = HueBands.of(Band.BLUE to Adj(s = 0.1f), Band.GREEN to Adj(s = 0.06f), Band.RED to Adj(s = 0.04f)))),
        zeiss("zeiss_textured", "Textured",
            "A 'masterpiece mood': darker, denser tones, strong local contrast, deep greens and skies, vignette.",
            LookParams(exposure = -0.2f, contrast = 0.45f, shadows = -0.2f, highlights = -0.12f, saturation = -0.1f, warmth = 0.03f,
                vignette = 0.3f, sharpness = 0.35f, skinProtect = 0.45f, highlightRoll = 0.2f,
                bands = HueBands.of(Band.GREEN to Adj(h = -6f, s = -0.15f, l = -0.1f), Band.BLUE to Adj(s = 0.04f, l = -0.12f),
                    Band.YELLOW to Adj(s = -0.08f)))),
        zeiss("zeiss_pop", "Pop",
            "Lens 'pop': high micro-contrast so subjects stand out, clean flare-free blacks and crisp, slightly cool colour.",
            LookParams(contrast = 0.28f, sharpness = 0.5f, shadows = -0.1f, highlights = -0.05f, saturation = 0.04f, highlightRoll = 0.3f,
                warmth = -0.03f, skinProtect = 0.5f, bands = HueBands.of(Band.BLUE to Adj(h = -3f, s = 0.06f, l = -0.06f)))),
        // ---- Hasselblad (OnePlus / OPPO)
        hassel("hb_natural", "Lifelike",
            "True-to-life colour, a film-style tone curve, wide but " +
                "gentle contrast and skin that stays smooth from highlight to shadow.",
            LookParams(contrast = 0.16f, highlightRoll = 0.55f, vibrance = 0.08f, warmth = 0.03f, skinProtect = 0.8f, sharpness = -0.08f,
                shadows = 0.04f, bands = HueBands.of(Band.GREEN to Adj(h = 4f, s = -0.06f), Band.YELLOW to Adj(s = -0.05f),
                    Band.BLUE to Adj(h = 3f, s = 0.03f), Band.ORANGE to Adj(s = -0.03f)))),
        hassel("hb_radiance", "Radiance",
            "A warm, golden-yellow light over the whole picture.",
            LookParams(warmth = 0.18f, highlightHue = 48f, highlightAmt = 0.3f, exposure = 0.1f, contrast = 0.06f, highlightRoll = 0.45f,
                saturation = 0.04f, skinProtect = 0.5f)),
        hassel("hb_serenity", "Serenity",
            "Calm, slightly blue, softly lit.",
            LookParams(warmth = -0.12f, shadowHue = 212f, shadowAmt = 0.3f, saturation = -0.1f, contrast = -0.04f, highlightRoll = 0.5f, skinProtect = 0.6f)),
        hassel("hb_emerald", "Emerald",
            "A stronger blue-green cast with emerald foliage and firmer contrast.",
            LookParams(warmth = -0.28f, tint = -0.07f, shadowHue = 190f, shadowAmt = 0.85f, highlightHue = 170f, highlightAmt = 0.12f, contrast = 0.16f, skinProtect = 0.6f,
                bands = HueBands.of(Band.GREEN to Adj(h = 10f, s = -0.04f), Band.AQUA to Adj(s = 0.08f), Band.BLUE to Adj(s = 0.08f)))),
        hassel("hb_xpan", "Pano Colour",
            "Colour film from a panoramic film camera: calm colour, soft shoulder, cool shadows, grain and corner fall-off. " +
                "Selecting it also switches photos to the 65:24 panoramic frame.",
            LookParams(contrast = 0.2f, fade = 0.06f, saturation = -0.12f, warmth = 0.06f, shadowHue = 192f, shadowAmt = 0.3f, highlightRoll = 0.5f,
                grain = 0.2f, vignette = 0.35f, skinProtect = 0.4f, bands = HueBands.of(Band.GREEN to Adj(h = -5f, s = -0.1f)))),
        hassel("hb_xpan_mono", "Pano B&W",
            "Black-and-white film in the panoramic frame: firm contrast, visible grain, dark corners. Also switches to 65:24.",
            LookParams(mono = 1f, contrast = 0.45f, grain = 0.3f, vignette = 0.35f, monoRed = 0.45f, monoGreen = 0.45f, monoBlue = 0.1f, highlightRoll = 0.3f)),
        // ---- Sony (Creative Look and S-Cinetone, as Sony describes them)
        sony("sony_scinetone", "Cine Soft",
            "Cinema-camera look: highlights roll off early and gently from about 70%, a toe that deepens shadows, " +
                "film-like colour and pleasing skin. Best for video; expose faces a little lower than usual.",
            LookParams(highlightRoll = 0.75f, contrast = 0.12f, shadows = -0.06f, highlights = -0.1f, saturation = -0.06f, skinProtect = 0.7f,
                skinSat = -0.03f, bands = HueBands.of(Band.GREEN to Adj(h = -4f, s = -0.12f), Band.BLUE to Adj(s = -0.05f), Band.ORANGE to Adj(s = -0.03f)))),
        sony("sony_pt", "Portrait Tone", "Skin in a soft tone, made for portraits.",
            LookParams(contrast = -0.08f, saturation = -0.05f, skinProtect = 0.8f, skinSat = -0.05f, skinBright = 0.04f, sharpness = -0.15f, highlightRoll = 0.45f)),
        sony("sony_nt", "Neutral Tone", "Lower saturation and sharpness, subdued tones; good for editing later.",
            LookParams(saturation = -0.25f, sharpness = -0.3f, contrast = -0.05f, highlightRoll = 0.35f)),
        sony("sony_vv", "Vivid Tone", "Higher saturation and contrast for flowers, greenery, sky and sea.",
            LookParams(saturation = 0.25f, contrast = 0.3f, sharpness = 0.1f, skinProtect = 0.5f,
                bands = HueBands.of(Band.GREEN to Adj(s = 0.08f), Band.BLUE to Adj(s = 0.1f)))),
        sony("sony_vv2", "Vivid Clear", "Bright, vivid colour with rich clarity.",
            LookParams(exposure = 0.1f, vibrance = 0.35f, saturation = 0.1f, contrast = 0.15f, shadows = 0.1f, sharpness = 0.25f, skinProtect = 0.5f)),
        sony("sony_fl", "Film Tone", "A moody finish from sharp contrast over calm colour, with rich skies and greens.",
            LookParams(saturation = -0.2f, contrast = 0.45f, shadows = -0.1f, warmth = -0.04f, fade = 0.04f, sharpness = 0.1f, skinProtect = 0.4f,
                bands = HueBands.of(Band.GREEN to Adj(h = -6f, s = 0.05f, l = -0.12f), Band.BLUE to Adj(h = -6f, s = 0.1f, l = -0.12f), Band.AQUA to Adj(s = 0.05f)))),
        sony("sony_in", "Instant", "A matte texture from held-back contrast and saturation.",
            LookParams(contrast = -0.35f, saturation = -0.3f, fade = 0.12f, warmth = 0.05f, highlightRoll = 0.4f)),
        sony("sony_sh", "Soft High-key", "Bright, clear, soft and airy.",
            LookParams(exposure = 0.3f, contrast = -0.2f, shadows = 0.2f, vibrance = 0.15f, highlightRoll = 0.5f, warmth = -0.02f, skinProtect = 0.6f)),
        sony("sony_bw", "Plain B&W", "Plain black-and-white.",
            LookParams(mono = 1f, contrast = 0.2f)),
        sony("sony_se", "Sepia", "Sepia monotone.",
            LookParams(mono = 1f, contrast = 0.1f, shadowHue = 32f, shadowAmt = 0.85f, highlightHue = 40f, highlightAmt = 0.25f)),
    )

    private const val SLAY = "LUT: slay (slaycamera.com), CC BY 4.0"
    private fun slay(id: String, name: String, file: String, desc: String, intensity: Float) =
        Look(id, name, STYLE, desc, cubeFile = "asset:luts/$file", defaultIntensity = intensity, credit = SLAY)

    /** Original everyday looks plus the five slay LUTs; the curated set shown first. */
    private val everyday = listOf(
        Look("vivid_punchy", "Vivid", STYLE, "Lively colour and clear contrast for bright days; skin and highlights stay natural.",
            LookParams(vibrance = 0.45f, saturation = 0.08f, contrast = 0.25f, shadows = -0.05f, highlightRoll = 0.35f, skinProtect = 0.6f,
                bands = HueBands.of(Band.BLUE to Adj(s = 0.1f), Band.GREEN to Adj(s = 0.06f)))),
        Look("rich_landscape", "Rich Landscape", STYLE, "Deep blue skies held back from clipping, lush but believable greens, open shadows.",
            LookParams(contrast = 0.2f, highlights = -0.25f, shadows = 0.1f, vibrance = 0.3f, highlightRoll = 0.45f, skinProtect = 0.5f,
                bands = HueBands.of(Band.BLUE to Adj(h = -4f, s = 0.15f, l = -0.12f), Band.AQUA to Adj(s = 0.1f),
                    Band.GREEN to Adj(h = -3f, s = 0.12f, l = -0.05f), Band.YELLOW to Adj(s = 0.05f)))),
        Look("warm_travel", "Warm Travel", STYLE, "Sunny warmth, clear turquoise water and sky, flattering skin. Made for holidays.",
            LookParams(warmth = 0.15f, vibrance = 0.2f, contrast = 0.12f, highlightHue = 40f, highlightAmt = 0.2f, shadows = 0.08f, highlightRoll = 0.45f,
                skinProtect = 0.55f, bands = HueBands.of(Band.BLUE to Adj(h = -6f, s = 0.08f), Band.ORANGE to Adj(s = 0.05f)))),
        Look("night_city", "Night City", STYLE, "Cool blue night, warm street lights kept from blowing out, signs that glow.",
            LookParams(exposure = -0.1f, contrast = 0.3f, shadows = -0.1f, highlightRoll = 0.7f, warmth = -0.12f, shadowHue = 220f, shadowAmt = 0.5f,
                highlightHue = 30f, highlightAmt = 0.25f, vibrance = 0.25f, skinProtect = 0.5f,
                bands = HueBands.of(Band.BLUE to Adj(s = 0.15f), Band.MAGENTA to Adj(s = 0.15f), Band.ORANGE to Adj(s = 0.1f)))),
        Look("soft_pastel", "Soft Pastel", STYLE, "Light and airy pastel: lifted shadows, gentle colour, a soft blush in the highlights.",
            LookParams(exposure = 0.2f, contrast = -0.3f, shadows = 0.2f, saturation = -0.25f, fade = 0.1f, highlightRoll = 0.55f,
                highlightHue = 330f, highlightAmt = 0.1f, skinProtect = 0.6f, sharpness = -0.1f)),
        Look("moody_cinema", "Moody Cinema", CINEMA, "Dark and dramatic: deep cool shadows, warm highlights, quieter colour, a hint of vignette.",
            LookParams(exposure = -0.25f, contrast = 0.35f, shadows = -0.15f, highlights = -0.1f, saturation = -0.2f, warmth = -0.06f,
                shadowHue = 200f, shadowAmt = 0.3f, highlightHue = 35f, highlightAmt = 0.2f, vignette = 0.25f, skinProtect = 0.55f,
                bands = HueBands.of(Band.GREEN to Adj(h = 8f, s = -0.25f), Band.ORANGE to Adj(s = -0.05f), Band.RED to Adj(h = 7f), Band.MAGENTA to Adj(h = 10f)))),
        Look("food_product", "Food & Product", STYLE, "Appetising warm reds, oranges and yellows, clean bright whites, no blue cast.",
            LookParams(exposure = 0.12f, warmth = 0.06f, contrast = 0.15f, highlightRoll = 0.45f, vibrance = 0.25f, skinProtect = 0.3f,
                bands = HueBands.of(Band.RED to Adj(s = 0.1f), Band.ORANGE to Adj(s = 0.12f, l = 0.03f), Band.YELLOW to Adj(s = 0.12f),
                    Band.GREEN to Adj(h = -2f, s = 0.08f), Band.BLUE to Adj(s = -0.1f)))),
        slay("slay_amber", "Amber", "slay-01-amber.cube", "slay Amber: golden-hour glow. Warm highlights, gentle contrast. Strong at full strength; starts at 70%.", 0.7f),
        slay("slay_fade", "Fade", "slay-02-fade.cube", "slay Fade: matte film. Lifted blacks, muted colour, soft highlights.", 0.85f),
        slay("slay_neon", "Neon", "slay-03-neon.cube", "slay Neon: night-city punch. Cyan shadows, magenta highlights; for lights and signs, not daylight. Starts at 60%.", 0.6f),
        slay("slay_film", "Film", "slay-04-film.cube", "slay Film: deep contrast, teal shadows, warm skin. A subtle everyday filmic look.", 1f),
        slay("slay_portrait", "Portrait", "slay-05-portrait.cube", "slay Portrait: warm skin, gentle contrast, soft highlight roll-off.", 0.9f),
    )

    /** The curated set, in the order people reach for them. */
    val ESSENTIALS = listOf("neutral", "true_tone", "soft_portrait", "vivid_punchy", "rich_landscape", "warm_travel", "golden_glow",
        "night_city", "teal_amber", "faded_vintage", "soft_pastel", "moody_cinema", "silver_mono", "retro_snapshot", "food_product",
        "slay_amber", "slay_fade", "slay_neon", "slay_film", "slay_portrait")

    val all: List<Look> = listOf(NEUTRAL) + brandLooks + everyday + listOf(
        // ---- Styles: true-to-life rendering with skin undertone control
        Look("true_tone", "True Color", STYLE,
            "Accurate, true-to-life colour: calmer saturation than the phone default, protected highlights, natural skin. " +
                "A good everyday baseline.",
            LookParams(saturation = -0.08f, contrast = 0.08f, highlightRoll = 0.4f, warmth = 0.03f, skinProtect = 0.4f, skinHue = 2f, sharpness = -0.1f)),
        Look("warm_tone", "Warm Undertone", STYLE, "Gently warmer scene and skin; whites stay clean.",
            LookParams(warmth = 0.14f, contrast = 0.06f, highlightRoll = 0.35f, skinHue = 2f, skinSat = 0.06f, skinBright = 0.04f)),
        Look("gold_tone", "Gold Undertone", STYLE, "Golden skin glow with sunlit warmth in the highlights.",
            LookParams(warmth = 0.16f, highlightHue = 42f, highlightAmt = 0.25f, contrast = 0.05f, skinHue = 3f, skinSat = 0.08f, skinBright = 0.06f, highlightRoll = 0.4f)),
        Look("rose_tone", "Rose Undertone", STYLE, "Rosy, healthy skin; a soft pink lift in the highlights.",
            LookParams(warmth = 0.04f, tint = 0.04f, highlightHue = 350f, highlightAmt = 0.12f, skinHue = -3f, skinSat = 0.05f, skinBright = 0.05f, highlightRoll = 0.4f)),
        Look("cool_tone", "Cool Undertone", STYLE, "Cooler, crisp scene; skin stays natural rather than blue.",
            LookParams(warmth = -0.2f, contrast = 0.1f, skinProtect = 0.75f, skinHue = -2f, highlightRoll = 0.3f)),
        Look("airy", "Airy", STYLE, "Bright and open: lifted shadows, soft contrast, luminous skin.",
            LookParams(exposure = 0.28f, contrast = -0.22f, saturation = -0.05f, highlightRoll = 0.55f, skinBright = 0.1f, skinProtect = 0.5f)),
        Look("deep", "Deep", STYLE, "Rich shadows and strong separation for drama, with true colour.",
            LookParams(exposure = -0.3f, contrast = 0.55f, saturation = 0.04f, highlightRoll = 0.15f, vignette = 0.3f, skinProtect = 0.5f, sharpness = 0.15f)),
        Look("hush", "Hush", STYLE, "Quiet and muted: lower saturation, slightly lifted blacks, calm contrast.",
            LookParams(saturation = -0.4f, contrast = -0.1f, fade = 0.08f, highlightRoll = 0.45f, skinProtect = 0.6f)),
        Look("dreamy", "Dreamy", STYLE, "Soft, glowing pastel with softened detail.",
            LookParams(exposure = 0.2f, contrast = -0.3f, saturation = -0.12f, fade = 0.12f, highlightHue = 330f, highlightAmt = 0.12f, sharpness = -0.5f, skinBright = 0.06f)),
        Look("soft_bw", "Soft B&W", MONO, "Gentle black-and-white with open shadows.",
            LookParams(mono = 1f, contrast = -0.05f, highlightRoll = 0.5f, fade = 0.06f)),
        Look("stark_bw", "Stark B&W", MONO, "Graphic, high-contrast black-and-white.",
            LookParams(mono = 1f, contrast = 0.85f, monoRed = 0.45f, monoGreen = 0.45f, monoBlue = 0.1f, exposure = -0.15f, sharpness = 0.3f)),
        // ---- Natural / photographic
        Look("natural", "Natural Color", NATURAL,
            "True-to-scene colour with a gentle highlight shoulder and slightly calmer saturation than the phone default.",
            LookParams(saturation = -0.12f, contrast = 0.06f, highlightRoll = 0.45f, skinProtect = 0.6f)),
        Look("soft_portrait", "Natural Portrait", NATURAL,
            "Flattering skin: lower contrast, warm-neutral tones, soft highlights and a slightly softened texture.",
            LookParams(contrast = -0.18f, warmth = 0.1f, saturation = -0.08f, highlightRoll = 0.55f, highlightHue = 22f,
                highlightAmt = 0.2f, sharpness = -0.25f, skinProtect = 0.8f)),
        Look("rich_contrast", "Rich Contrast", NATURAL, "Punchy tonal separation, deep blacks, full colour.",
            LookParams(contrast = 0.7f, saturation = 0.12f, exposure = -0.12f, sharpness = 0.25f, skinProtect = 0.4f)),
        Look("documentary", "Documentary", NATURAL, "Sober, desaturated realism with firm contrast; colour stays truthful but quiet.",
            LookParams(saturation = -0.38f, contrast = 0.22f, warmth = -0.05f, highlightRoll = 0.3f, sharpness = 0.2f)),
        Look("clean_commercial", "Clean Commercial", NATURAL, "Bright, crisp, neutral whites; product-shot clarity.",
            LookParams(exposure = 0.2f, contrast = 0.18f, vibrance = 0.2f, warmth = -0.06f, highlightRoll = 0.25f, sharpness = 0.35f)),
        // ---- Mood presets (original, matched to the moods of popular pro-mode recipes)
        Look("ink_wash", "Ink Wash", MOOD, "Soft, painterly and muted, like ink on paper: low saturation, cool grey cast, softened detail.",
            LookParams(saturation = -0.5f, contrast = 0.12f, warmth = -0.1f, fade = 0.08f, sharpness = -0.45f, shadowHue = 210f, shadowAmt = 0.25f, skinProtect = 0.3f)),
        Look("crimson_dusk", "Crimson Dusk", MOOD, "Sunset reds and oranges pushed to glow; soft contrast so the sky stays open.",
            LookParams(contrast = -0.25f, warmth = 0.32f, tint = 0.06f, saturation = 0.25f, highlightHue = 18f, highlightAmt = 0.7f,
                shadowHue = 280f, shadowAmt = 0.25f, highlightRoll = 0.5f, sharpness = 0.25f)),
        Look("faded_years", "Faded Years", MOOD, "Dreamy nostalgia: half the contrast, warm fade, gentle grain.",
            LookParams(contrast = -0.5f, fade = 0.3f, warmth = 0.25f, saturation = -0.18f, highlightHue = 40f, highlightAmt = 0.3f, grain = 0.25f, vignette = 0.25f)),
        Look("silk_green", "Silk Green", MOOD, "Fresh, soft greens with airy highlights; made for gardens and foliage.",
            LookParams(exposure = 0.1f, contrast = -0.12f, tint = -0.08f, vibrance = 0.3f, shadowHue = 150f, shadowAmt = 0.25f, highlightRoll = 0.4f, skinProtect = 0.7f)),
        Look("bright_youth", "Bright Youth", MOOD, "Light, airy and warm, pastel highlights; flattering for people outdoors.",
            LookParams(exposure = 0.3f, contrast = -0.2f, warmth = 0.15f, saturation = -0.05f, highlightHue = 35f, highlightAmt = 0.25f, fade = 0.06f, skinProtect = 0.8f)),
        Look("forest_path", "Forest Path", MOOD, "Deep emerald greens, cool shadows, quiet moody contrast.",
            LookParams(exposure = -0.2f, contrast = 0.3f, tint = -0.08f, warmth = -0.08f, saturation = -0.05f, shadowHue = 165f, shadowAmt = 0.45f, vignette = 0.35f, skinProtect = 0.5f)),
        Look("clear_moon", "Clear Moon", MOOD, "Clean night: +0.3 EV lift, blue-cool shadows, neutral highlights, light protection for lamps.",
            LookParams(exposure = 0.3f, contrast = 0.12f, warmth = -0.25f, shadowHue = 220f, shadowAmt = 0.5f, highlightRoll = 0.6f, saturation = -0.08f)),
        Look("golden_glow", "Golden Hour", MOOD, "The viral golden-hour edit: brighter, softer contrast, warm glow in skin and highlights.",
            LookParams(exposure = 0.25f, contrast = -0.25f, warmth = 0.3f, saturation = 0.12f, highlightHue = 38f, highlightAmt = 0.5f, highlightRoll = 0.5f, skinProtect = 0.2f)),
        Look("digicam", "Digicam CCD", MOOD, "2000s compact digital: punchy saturated colour, cool whites, crisp edges, slightly crushed blacks.",
            LookParams(contrast = 0.35f, saturation = 0.3f, warmth = -0.18f, tint = 0.05f, exposure = 0.05f, sharpness = 0.6f, grain = 0.12f)),
        // ---- Film
        Look("warm_film", "Warm Film", FILM, "Amber highlights, warm mids, gentle film contrast and fine grain.",
            LookParams(warmth = 0.45f, contrast = 0.15f, highlightHue = 36f, highlightAmt = 0.55f, shadowHue = 20f, shadowAmt = 0.15f,
                fade = 0.07f, highlightRoll = 0.45f, grain = 0.15f)),
        Look("cool_film", "Cool Film", FILM, "Steel-blue shadows, cool restrained colour, crisp.",
            LookParams(warmth = -0.35f, saturation = -0.15f, shadowHue = 205f, shadowAmt = 0.7f, contrast = 0.15f, highlightRoll = 0.3f)),
        Look("color_negative", "Classic Color Negative", FILM, "Lifted blacks, soft shoulder, warm mids and cyan shadows like scanned negative film.",
            LookParams(contrast = -0.08f, fade = 0.22f, warmth = 0.18f, shadowHue = 190f, shadowAmt = 0.65f, highlightHue = 42f, highlightAmt = 0.4f,
                highlightRoll = 0.6f, saturation = -0.05f, grain = 0.3f, skinProtect = 0.4f)),
        Look("faded_vintage", "Vintage Film", FILM, "Heavy fade, warm cast, washed colour and vignette.",
            LookParams(fade = 0.5f, saturation = -0.35f, warmth = 0.3f, contrast = -0.12f, highlightHue = 45f, highlightAmt = 0.45f, grain = 0.35f, vignette = 0.4f)),
        Look("golden_film", "Golden Film", FILM, "Strong gold across the frame: sunlit, saturated, warm shadows.",
            LookParams(warmth = 0.48f, highlightHue = 42f, highlightAmt = 0.7f, shadowHue = 30f, shadowAmt = 0.3f, contrast = 0.2f, saturation = 0.15f, grain = 0.15f)),
        Look("soft_romantic", "Soft Romantic", FILM, "Pastel pink highlights, lifted blacks, low contrast.",
            LookParams(contrast = -0.3f, fade = 0.22f, highlightHue = 345f, highlightAmt = 0.35f, warmth = 0.08f, saturation = -0.12f, highlightRoll = 0.5f,
                sharpness = -0.15f, skinProtect = 0.5f)),
        Look("fine_grain", "Fine-Grain Film", FILM, "Neutral film tonality with visible fine grain.",
            LookParams(contrast = 0.12f, fade = 0.08f, highlightRoll = 0.45f, grain = 0.55f, saturation = -0.05f)),
        Look("retro_snapshot", "Retro Snapshot", FILM, "Warm, saturated consumer-print look with a strong vignette.",
            LookParams(warmth = 0.35f, saturation = 0.28f, contrast = 0.3f, fade = 0.12f, tint = 0.1f, vignette = 0.6f, grain = 0.25f)),
        Look("home_movie", "Retro Home Movie", FILM, "Faded warm colour, heavy grain and vignette.",
            LookParams(fade = 0.4f, warmth = 0.45f, saturation = -0.2f, grain = 0.8f, vignette = 0.65f, contrast = 0.12f, sharpness = -0.3f)),
        // ---- Monochrome
        Look("silver_mono", "Black & White", MONO, "Neutral black-and-white, full tonal range, soft shoulder.",
            LookParams(mono = 1f, contrast = 0.2f, highlightRoll = 0.4f, sharpness = 0.15f)),
        Look("hc_mono", "High-Contrast B&W", MONO, "Punchy black-and-white with a red-filter mix: dark skies, bright skin.",
            LookParams(mono = 1f, contrast = 0.75f, monoRed = 0.6f, monoGreen = 0.35f, monoBlue = 0.05f, exposure = -0.1f, sharpness = 0.3f)),
        Look("dramatic_mono", "Dramatic Monochrome", MONO, "Deep blacks, bright whites, heavy vignette and grain.",
            LookParams(mono = 1f, contrast = 0.9f, exposure = -0.2f, vignette = 0.6f, grain = 0.3f)),
        // ---- Cinema
        Look("teal_amber", "Cinematic Teal & Amber", CINEMA, "Teal shadows, amber highlights; skin stays natural.",
            LookParams(shadowHue = 188f, shadowAmt = 0.9f, highlightHue = 32f, highlightAmt = 0.75f, contrast = 0.22f, saturation = -0.05f,
                highlightRoll = 0.35f, skinProtect = 0.6f)),
        Look("muted_cinema", "Muted Cinema", CINEMA, "Low saturation, soft contrast, green-cyan shadows.",
            LookParams(saturation = -0.45f, contrast = -0.08f, shadowHue = 185f, shadowAmt = 0.35f, fade = 0.12f, highlightRoll = 0.45f, skinProtect = 0.4f)),
        Look("moody_night", "Moody Night", CINEMA, "Cool deep shadows, controlled lamps and neon.",
            LookParams(exposure = -0.3f, contrast = 0.3f, warmth = -0.3f, shadowHue = 222f, shadowAmt = 0.75f, saturation = -0.1f, highlightRoll = 0.65f)),
        Look("neutral_film", "Neutral Film", CINEMA, "Film-like toe and shoulder without a colour cast.",
            LookParams(contrast = 0.12f, highlightRoll = 0.55f, fade = 0.05f, saturation = -0.08f)),
        Look("cool_cinema", "Cool Cinema", CINEMA, "Blue-steel grade with neutral skin.",
            LookParams(warmth = -0.4f, shadowHue = 210f, shadowAmt = 0.65f, highlightHue = 30f, highlightAmt = 0.2f, contrast = 0.18f, skinProtect = 0.7f)),
        Look("vivid_cinema", "Vivid", CINEMA, "High-saturation grade for colourful scenes.",
            LookParams(vibrance = 0.6f, saturation = 0.2f, contrast = 0.2f, skinProtect = 0.5f)),
        Look("minimal_film", "Minimalist Film", CINEMA, "Quiet, low-contrast, desaturated.",
            LookParams(contrast = -0.3f, saturation = -0.55f, fade = 0.15f, highlightRoll = 0.45f)),
        Look("flat", "Flat (creative)", CINEMA,
            "A low-contrast, low-saturation grade applied to processed video for later grading. It is NOT a Log " +
                "profile: it cannot recover highlights or shadows the camera has already clipped.",
            LookParams(contrast = -0.65f, saturation = -0.35f, highlightRoll = 0.7f, fade = 0.14f)),
    )

    fun byId(id: String?): Look? = all.firstOrNull { it.id == id }
}
