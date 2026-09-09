package org.flightdesk.radar;

import android.content.res.Configuration;

/** Window-size policy shared by navigation and content, including split-screen windows. */
final class LayoutProfile {
  final int width, height, railWidth, contentWidth;
  final boolean rail, compactCards, wideWeather, scrollCards;

  LayoutProfile(Configuration c) {
    width = c.screenWidthDp;
    height = c.screenHeightDp;
    rail = width >= 700 && width > height;
    railWidth = width >= 1100 ? 176 : 140;
    contentWidth = width - 24 - (rail ? railWidth + 6 : 0);
    compactCards = contentWidth < 600;
    wideWeather = contentWidth >= 600;
    scrollCards = compactCards || height < 400 || c.fontScale > 1.2f;
  }
}
