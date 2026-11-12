package io.github.aiassetmigrate.gui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ThemeTest {
    @Test void darkThemeIsPackagedAndDefinesTheStyleClassesTheGuiUses() throws Exception {
        String url = AssetMigrateApplication.stylesheet();
        String css;
        try (InputStream in = URI.create(url).toURL().openStream()) {
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String selector : new String[]{".root", ".h2", ".h3", ".brand", ".muted", ".field-label", ".button.primary",
                ".button.destructive", ".text-input", ".table-view", ".app-header", ".stepper", ".step", ".step-index",
                ".step-label", ".step-current", ".step-complete", ".step-connector", ".step-host", ".step-view",
                ".app-footer", ".status", ".drawer", ".tool-toggle", ".segmented", ".choice-card", ".choice-title",
                ".badge", ".badge-success", ".badge-warning", ".badge-danger", ".callout", ".callout-danger", ".titled-pane"}) {
            assertTrue(css.contains(selector), "missing " + selector);
        }
    }
}
