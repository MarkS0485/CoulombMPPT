package app.coulombmppt.ui.theme

import androidx.compose.ui.graphics.Color

// CoulombMPPT brand palette
//   --color-coulomb-navy:      #012169
//   --color-coulomb-red:       #C8102E
//   --color-coulomb-burgundy:  #6b0c1c
// Body background:          #F1F3F9
val CoulombNavy     = Color(0xFF012169)
val CoulombNavyDk   = Color(0xFF0A1F6B)
val CoulombRed      = Color(0xFFC8102E)
val CoulombRedDk    = Color(0xFF9B0D23)
val CoulombBurgundy = Color(0xFF6B0C1C)

// Light surfaces — same as the website body + glass-panel cards.
val Body0      = Color(0xFFF1F3F9)   // page background
val Body1      = Color(0xFFFFFFFF)   // primary card / surface
val Body2      = Color(0xFFF6F7FB)   // recessed surface
val LineGrey   = Color(0xFFE2E5EE)   // hairline borders
val LineGreyDk = Color(0xFFCBD2DF)   // outline / divider

// Text on light surfaces.
val InkHi = Color(0xFF0F172A)   // body
val InkMd = Color(0xFF374151)   // secondary
val InkLo = Color(0xFF6B7280)   // captions, labels

// Status colours.
val OkGreen   = Color(0xFF16A34A)
val WarnAmber = Color(0xFFD97706)
val ErrRed    = CoulombRed

// Unit 001 ("Domestic dwelling") accent from the sister coulombmonitor app —
// warm copper / amber-700. Adopted as our primary unit accent because
// Mark's MPPT install IS a small domestic solar+battery setup and we want
// the two apps to feel like siblings.
val UnitAccent001 = Color(0xFFB45309)   // amber-700

// MPPT-domain accents — used on tiles + the energy-flow diagram to keep
// "where is the power coming from / going to" instantly readable.
//   PV side / charging:  copper (Unit 001 accent — also reads as "sun")
//   Battery side:        navy (matches the brand)
//   Load side:           burgundy
val SolarAmber    = UnitAccent001
val BatteryBlue   = CoulombNavy
val LoadNavy      = CoulombBurgundy
val ChargingGreen = OkGreen

// Legacy aliases kept so any ported components compile cleanly.
val Surface0    = Body0
val Surface1    = Body1
val Surface2    = Body2
val SurfaceLine = LineGrey
val OnSurfaceHi = InkHi
val OnSurfaceMd = InkMd
val OnSurfaceLo = InkLo
