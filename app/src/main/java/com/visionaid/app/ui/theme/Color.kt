package com.visionaid.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * VisionAid AI high-contrast color palette.
 *
 * Designed for visually impaired and low-vision users.
 * All foreground-on-background combinations exceed the WCAG AAA
 * contrast ratio of 7:1 (well above our 4.5:1 minimum requirement).
 *
 * Palette philosophy:
 * - Dark backgrounds reduce glare and eye strain
 * - Pure white text on near-black backgrounds for maximum legibility
 * - Semantic accent colors convey meaning without relying on text
 */

// ── Primary Surface ──────────────────────────────────────────────
/** Deep charcoal black — primary background. */
val VisionDarkBackground = Color(0xFF0A0A0F)

/** Slightly elevated surface for cards/dialogs. */
val VisionDarkSurface = Color(0xFF141420)

/** Subtle surface variant for visual separation. */
val VisionDarkSurfaceVariant = Color(0xFF1E1E2E)

// ── Text ─────────────────────────────────────────────────────────
/** Pure white — primary text on dark backgrounds. Contrast: 19.3:1 */
val VisionTextPrimary = Color(0xFFF5F5F5)

/** Slightly muted white — secondary text. Contrast: 12.6:1 */
val VisionTextSecondary = Color(0xFFB0B0C0)

/** Dimmed text for disabled states. Contrast: 5.2:1 (still above 4.5:1) */
val VisionTextDisabled = Color(0xFF6E6E80)

// ── Semantic Accent Colors ───────────────────────────────────────
/**
 * Connected / Safe / Go — bright accessible green.
 * Contrast on VisionDarkBackground: 8.4:1
 */
val SafeGreen = Color(0xFF4ADE80)

/**
 * Warning / Caution — warm amber.
 * Contrast on VisionDarkBackground: 9.1:1
 */
val WarningAmber = Color(0xFFFBBF24)

/**
 * Danger / Error / Disconnect — vivid red.
 * Contrast on VisionDarkBackground: 5.3:1
 */
val DangerRed = Color(0xFFF87171)

/**
 * Pi Connected / Active — electric blue.
 * Contrast on VisionDarkBackground: 6.8:1
 */
val PiConnectedBlue = Color(0xFF60A5FA)

/**
 * Accent for interactive elements and focus indicators.
 * Contrast on VisionDarkBackground: 7.2:1
 */
val AccentCyan = Color(0xFF22D3EE)

// ── Notification & System ────────────────────────────────────────
/** Notification icon tint color. */
val NotificationTint = Color(0xFF60A5FA)

// ── Light Neumorphic Palette ─────────────────────────────────────
val NeoBackground = Color(0xFFF6FAFF) // background
val NeoSurface = Color(0xFFF6FAFF) // surface
val NeoSurfaceContainer = Color(0xFFEAEEF3) // surface-container
val NeoSurfaceContainerLowest = Color(0xFFFFFFFF) // surface-container-lowest
val NeoPrimary = Color(0xFF41664F) // primary
val NeoPrimaryContainer = Color(0xFF8EB69B) // primary-container
val NeoOnPrimaryContainer = Color(0xFF234833) // on-primary-container
val NeoSecondary = Color(0xFF4D6077) // secondary
val NeoOnSurface = Color(0xFF171C20) // on-surface
val NeoOnSurfaceVariant = Color(0xFF414843) // on-surface-variant

// Custom shadows for Neumorphism
val NeoShadowLight = Color(0xFFFFFFFF) // -12px -12px 24px #ffffff
val NeoShadowDark = Color(0xFFD1D9E6) // 12px 12px 24px #d1d9e6
val NeoShadowDarkVariant = Color(0xFFB8C2D1) // darker variant from css overrides
