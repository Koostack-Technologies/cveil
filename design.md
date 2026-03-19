# Design System Strategy: The Sentinel Aesthetic

## 1. Overview & Creative North Star
**The Creative North Star: "The Digital Vault"**

This design system moves beyond the standard SaaS dashboard into a high-fidelity, editorial-grade security environment. In a world of compliance and security, "clutter" is a vulnerability. Our "v2" evolution focuses on **Atmospheric Precision**: a UI that feels like a sophisticated heads-up display—breathable, authoritative, and impenetrable.

We break the "template" look by rejecting the rigid 1px border grid. Instead, we utilize **Intentional Asymmetry** and **Tonal Depth**. By layering surfaces of varying "weights" and using high-contrast typography scales (Inter vs. Space Grotesk), we create a signature experience that signals professional-grade security without the "industrial" clunkiness of v1.

---

## 2. Color Theory & Tonal Architecture
The palette is rooted in deep midnight blues and teals, optimized for long-term monitoring and high-stakes decision-making.

### The "No-Line" Rule
**Standard 1px solid borders are prohibited for sectioning.**
Structural boundaries must be defined solely through background color shifts. For example, a `surface-container-low` section sitting on a `surface` background creates a natural, sophisticated edge. We define space through mass, not lines.

### Surface Hierarchy & Nesting
Treat the UI as a physical stack of materials.
* **Base:** `surface` (#0b1326) – The foundation.
* **Secondary Sections:** `surface-container-low` (#131b2e) – Subtle grouping.
* **Active/Interactive Layers:** `surface-container-high` (#222a3d) – Elements that demand focus.
* **Nesting Logic:** Always place a lighter container inside a darker one (e.g., a `surface-container-highest` card inside a `surface-container` sidebar) to create "natural lift."

### The "Glass & Gradient" Rule
To escape the "flat" feel of v1, floating elements (Modals, Hover Menus) should use **Glassmorphism**:
* **Fill:** `surface_variant` at 60% opacity.
* **Effect:** `backdrop-blur: 12px`.
* **Signature Texture:** Main CTAs or Hero status cards should utilize a linear gradient from `primary` (#4fdbc8) to `primary_container` (#14b8a6) at a 135° angle to provide depth and "energy."

---

## 3. Typography: The Editorial Engine
We utilize three distinct typefaces to separate function from feeling.

* **The Display (Manrope):** Used for large headers (`display-lg` to `headline-sm`). Its geometric warmth provides a premium, "v2" tech-forward feel.
* **The Interface (Inter):** Used for all functional text (`title-lg` to `body-sm`). It is the workhorse for high-density compliance data.
* **The Technical (Space Grotesk):** Exclusively for `label-md` and `label-sm`. This monospaced-adjacent feel is used for IDs, timestamps, and status tags, leaning into the "security platform" persona.

**Editorial Tip:** Use `display-md` (2.75rem) paired with `body-sm` (0.75rem) with wide letter-spacing for metadata to create high-contrast, professional layouts.

---

## 4. Elevation & Depth
In this system, elevation is an optical illusion created by light, not structure.

* **Tonal Layering:** Avoid shadows for static cards. Use the difference between `surface_container_lowest` (#060e20) and `surface_container` (#171f33) to separate content.
* **Ambient Shadows:** For floating elements (popovers), use a diffused shadow: `0 20px 40px rgba(0, 0, 0, 0.4)`. The shadow must never be gray; it should be a darker shade of the background color.
* **The "Ghost Border" Fallback:** If a border is required for accessibility, use the `outline_variant` (#3c4947) at **15% opacity**. This creates a "suggestion" of a boundary that disappears into the background.

---

## 5. Components: Functional Refinement

### Buttons
* **Primary:** Gradient fill (`primary` to `primary_container`), `on_primary` text. No border. Roundedness: `md` (0.375rem).
* **Secondary:** `surface_container_highest` fill with a `ghost border`.
* **Tertiary:** Ghost button (no fill) with `primary` text. Use for low-emphasis actions.

### Status Chips (Security Indicators)
* **Design:** Use `label-sm` (Space Grotesk) in all-caps.
* **Critical:** `on_error_container` text on `error_container` fill.
* **Safe:** `on_secondary_container` text on `secondary_container` fill.
* **Note:** Chips should have a `full` (9999px) roundedness to contrast against the sharp container geometry.

### Input Fields
* **Static State:** `surface_container_low` fill, no border. `on_surface_variant` placeholder text.
* **Focus State:** 1px "Ghost Border" using `primary` at 40% opacity and a subtle `surface_tint` outer glow.
* **Labeling:** Labels should use `label-md` and be positioned 0.5rem (2) above the input.

### Cards & Lists (The Divider Rule)
**Divider lines are strictly forbidden.**
Separate list items using `1.5` (0.375rem) of vertical whitespace. If items need distinct separation, alternate backgrounds between `surface` and `surface_container_low` (Zebra striping 2.0).

### Security-Specific Components
* **The Pulse Monitor:** A small 8px circle using the `primary` color with a `ping` animation to indicate real-time system health.
* **The Data Glass:** A large `surface_variant` container with a 20px backdrop blur used for high-level compliance summaries.

---

## 6. Do’s and Don’ts

### Do:
* **Use Whitespace as a Tool:** Use the `24` (6rem) spacing for section breathing room. Compliance data is heavy; the UI must be light.
* **Layer Surfaces:** Place `surface_container_high` elements on top of `surface_dim` to create focus.
* **Respect the Type Scale:** Use `Space Grotesk` for all numeric data (IDs, IP addresses, dates) to maintain the "high-tech" feel.

### Don’t:
* **Don't Use Pure Black:** Always use `surface` (#0b1326). Pure #000000 kills the depth and feels "cheap."
* **Don't Overuse the Primary Teal:** Use the `primary` color only for CTAs, success states, and key brand moments. If everything is teal, nothing is important.
* **Don't Use Heavy Shadows:** If a container needs to stand out, change its color, don't just add a drop shadow.