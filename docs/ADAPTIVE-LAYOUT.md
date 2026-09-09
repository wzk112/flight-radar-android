# Responsive window layout

LayoutProfile uses the current Configuration screenWidthDp/screenHeightDp rather than device model or physical pixels. Navigation rail requires width >=700dp and landscape; rail width is 140dp (176dp above 1100dp). Remaining content below 600dp uses vertically stacked flight cards and two-column metrics. Weather splits only when content is at least 600dp wide.

Compact cards, windows shorter than 400dp, or fontScale >1.2 use scroll containers. Navigation controls are at least 48dp high and the rail scrolls. Cutout and visible system bar insets are applied to the root. Configuration changes rebuild content without reinitializing network clients or saved settings.

DotMatrixTextView and DotMatrixButton delegate size/layout changes to Android and invalidate the dot cache so automatic text sizing follows the current bounds.

Validation: build/lint; DeviceChecks on Xiaomi 17 Ultra; AdaptiveChecks screenshots in portrait and simulated Android display configurations (16:10 tablet, 4:3 tablet, narrow phone). These are rendering checks on a phone, not physical tablet certification. Scrollable content may intentionally extend beyond the viewport.
