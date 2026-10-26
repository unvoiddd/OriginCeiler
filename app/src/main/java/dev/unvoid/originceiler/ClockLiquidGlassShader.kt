package dev.unvoid.originceiler

/**
 * The shared glass ([KyantGlass.Renderer]) on shapes given only by their alpha: clock digits.
 *
 * Same model as every other glass surface, with the rounded-rect SDF replaced by the distance to
 * the digit's own edge (ring search, then a short bisection): a lens band [refractHeight] wide
 * along the contour bending the backdrop with Kyant's circle map, a flat middle, the theme
 * dimming / tint, and the rim highlight (thin line + inward sheen, lit by the light angle).
 * The backdrop arrives already blurred and is sampled once per pixel.
 */
object ClockLiquidGlassShader {
    const val AGSL_SOURCE = """
        uniform shader content;
        uniform shader backdrop;
        uniform float  hasBackdrop;
        uniform float  refractHeight;
        uniform float  refractAmount;
        uniform float  dispersion;
        uniform float  lightAngle;
        uniform float  rimWidth;
        uniform float  rimAlpha;
        uniform float  sheenWidth;
        uniform float  sheenAlpha;
        uniform float  saturation;
        uniform float  brightness;
        uniform float  dim;
        uniform float4 surfaceTint;
        uniform float4 tintColor;
        uniform float  tintStrength;

        float circleMap(float x) { return 1.0 - sqrt(max(0.0, 1.0 - x * x)); }

        float3 vibrant(float3 c) {
            float l = dot(c, float3(0.2126, 0.7152, 0.0722));
            return clamp(mix(float3(l), c, saturation) * brightness, 0.0, 1.0);
        }

        half4 main(float2 p) {
            float a = float(content.eval(p).a);
            if (a <= 0.003) return half4(0.0);

            // Distance to the edge and the outward normal: the first ring that reaches outside,
            // refined along its outward direction.
            float d = 80.0;
            float2 n = float2(0.0);
            float prev = 0.0;
            for (int k = 0; k < 11; k++) {
                float r = 1.0 + float(k * k) * 0.7;
                float2 acc = float2(0.0);
                float outside = 0.0;
                for (int j = 0; j < 8; j++) {
                    float ang = float(j) * 0.7853982;
                    float2 dir = float2(cos(ang), sin(ang));
                    float o = 1.0 - float(content.eval(p + dir * r).a);
                    acc += dir * o;
                    outside += o;
                }
                if (outside > 0.35) {
                    n = length(acc) > 0.0001 ? normalize(acc) : float2(0.0);
                    float lo = prev;
                    float hi = r;
                    for (int i = 0; i < 4; i++) {
                        float m = (lo + hi) * 0.5;
                        if (float(content.eval(p + n * m).a) < 0.5) { hi = m; } else { lo = m; }
                    }
                    d = (lo + hi) * 0.5;
                    break;
                }
                prev = r;
            }
            // Anti-aliased edge pixels sit on the contour.
            if (a < 0.999) d = min(d, a);

            float3 col;
            if (hasBackdrop > 0.5) {
                float2 offset = float2(0.0);
                if (refractHeight > 0.0 && d < refractHeight) {
                    float x = 1.0 - d / refractHeight;
                    offset = -n * circleMap(x) * refractAmount;
                }
                if (dispersion > 0.5 && length(offset) > 0.01) {
                    col = float3(
                        float(backdrop.eval(p + offset * 1.12).r),
                        float(backdrop.eval(p + offset).g),
                        float(backdrop.eval(p + offset * 0.88).b));
                } else {
                    col = float3(backdrop.eval(p + offset).rgb);
                }
                col = vibrant(col);
                col *= (1.0 - dim);
            } else {
                col = float3(0.62, 0.64, 0.68);
            }
            col = mix(col, surfaceTint.rgb, surfaceTint.a);
            col = mix(col, tintColor.rgb, clamp(tintStrength / 100.0, 0.0, 1.0) * tintColor.a);

            // Rim highlight on both lit sides plus a soft inward sheen.
            float2 light = float2(cos(lightAngle), sin(lightAngle));
            float lit = abs(dot(n, light));
            float rim = 1.0 - smoothstep(rimWidth * 0.5, rimWidth * 1.5, d);
            float sheen = exp(-d / max(sheenWidth, 0.5));
            float v = clamp((rim * rimAlpha + sheen * sheenAlpha) * lit, 0.0, 1.0);
            col = clamp(col + v, 0.0, 1.0);

            return half4(half3(col * a), half(a));
        }
    """
}
