package dev.unvoid.originceiler;

/** Original preset variants; parameters come from MIUIAod's preset catalog. */
final class NativeHyperOsStyles {
    static final class Style {
        final String id, template, title, preview;
        final int variant, font, weight;
        final boolean vertical;

        Style(String suffix, String template, int variant, int font, int weight, boolean vertical, String title) {
            this.id = "hyperos." + suffix;
            this.template = template;
            this.variant = variant;
            this.font = font;
            this.weight = weight;
            this.vertical = vertical;
            this.title = title;
            this.preview = "hyperos-" + suffix.replace('.', '-') + ".png";
        }
    }

    static final Style[] ALL = {
        new Style("classic.vertical", "classic_plus", 21, 21, 420, true, "HyperOS · 经典纵排"),
        new Style("classic.horizontal", "classic", 21, 21, 420, false, "HyperOS · 经典横排"),
        new Style("all_in_one.1", "all_in_one", 1, 21, 420, false, "HyperOS · 经典大字"),
        new Style("all_in_one.2", "all_in_one", 2, 21, 378, false, "HyperOS · 经典横排新款"),
        new Style("all_in_one.3", "all_in_one", 3, 21, 420, true, "HyperOS · 经典双行"),
        new Style("all_in_one.4", "all_in_one", 4, 21, 700, true, "HyperOS · 大字 A"),
        new Style("all_in_one.5", "all_in_one", 5, 22, 450, false, "HyperOS · 大字 B"),
        new Style("all_in_one.6", "all_in_one", 6, 21, 700, false, "HyperOS · 杂志数字"),
        new Style("all_in_one.7", "all_in_one", 7, 22, 450, true, "HyperOS · 大字 C"),
        new Style("all_in_one.8", "all_in_one", 8, 23, 630, false, "HyperOS · 大字 D"),
        new Style("rhombus.4", "rhombus", 4, 21, 420, true, "HyperOS · 菱形"),
        new Style("eastern_a.1", "eastern_a", 1, 21, 420, true, "HyperOS · 东方美学 A"),
        new Style("eastern_b.2", "eastern_b", 2, 21, 420, true, "HyperOS · 东方美学 B"),
        new Style("eastern_c.1", "eastern_c", 1, 21, 420, true, "HyperOS · 东方美学 C"),
        new Style("magazine_a.3", "magazine_a", 3, 21, 420, false, "HyperOS · 杂志 A"),
        new Style("magazine_b.1", "magazine_b", 1, 21, 420, false, "HyperOS · 杂志 B"),
        new Style("magazine_c.5", "magazine_c", 5, 21, 420, false, "HyperOS · 杂志 C"),
        new Style("doodle.1", "doodle", 1, 21, 420, false, "HyperOS · 涂鸦")
    };

    static Style find(String id) {
        if (id == null) return null;
        if (id.equals("hyperos.doodle.2") || id.endsWith("doodle.2"))
            return new Style("doodle.2", "doodle", 1, 21, 420, false, "HyperOS · 涂鸦");
        for (Style style : ALL) {
            if (style.id.equals(id) || id.endsWith(style.id) || id.equals(style.id.replace("hyperos.", "")) || id.endsWith(style.id.replace("hyperos.", ""))) {
                return style;
            }
        }
        return null;
    }

    private NativeHyperOsStyles() {}
}
