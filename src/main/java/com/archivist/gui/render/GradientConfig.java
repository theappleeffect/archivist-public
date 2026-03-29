package com.archivist.gui.render;

/**
 * Vertical gradient configuration for full-screen background.
 * Top->bottom blend via fillGradient.
 */
public record GradientConfig(int topColor, int bottomColor) {}
