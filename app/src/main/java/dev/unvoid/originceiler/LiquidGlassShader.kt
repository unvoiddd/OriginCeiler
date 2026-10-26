package dev.unvoid.originceiler

object LiquidGlassShader {
    const val AGSL_SOURCE = """
            uniform shader content;
            uniform float  margin;
            uniform float2 viewSize;
            uniform float4 shape1;    // 真实主形状（触摸凸起的尺度用它）
            uniform float4 radii1;
            uniform float4 shape1L;   // 主形状：平边方向延伸到视图外，那条边就没有斜面（视图矩形外硬切）
            uniform float  rimSoft;   // 边缘柔化：折射带内沿法线方向的抹匀宽度（px，0 = 关）
            uniform float4 shape2;
            uniform float  radius2;
            uniform float  blendK;
            uniform float  bevel;
            uniform float  refractPx;
            uniform float  falloff;      // 折射剖面：> 0 为逆幂（引力透镜）衰减指数，0 = 平方斜面
            uniform float  refractDir;   // -1 向内采样（默认，与 iOS 一致：内侧压缩镜像）/ +1 向外（可选的凸透镜模式）
            uniform float2 sampleLo;     // 采样安全区（录制内容坐标）：区外没有内容，读到的是透明黑
            uniform float2 sampleHi;
            uniform float  dispersion;
            uniform float2 lightDir;
            uniform float  specStrength;
            uniform float  rimBandMax;   // 贴边高光带宽度上限（px，小控件按短边收）
            uniform float  bottomEdgeSoftening;
            uniform float4 tintColor;
            uniform float  adaptiveTint;
            uniform float  materialStrength;
            uniform float4 glassTint;
            uniform float  dimAmount;
            uniform float  highlightProtection;
            uniform float  transmissionAttenuation;
            uniform float  satFactor;
            uniform float  press;
            uniform float2 touchPos;
            uniform float  touchAmp;

            float sdRoundedBox(float2 p, float2 b, float r) {
                r = clamp(r, 0.0, min(b.x, b.y));
                float2 q = abs(p) - b + r;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            // 逐角半径版：r = (左上, 右上, 右下, 左下)，按 p 所在象限选半径
            float sdRoundedBox4(float2 p, float2 b, float4 r) {
                float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);
                rx = clamp(rx, 0.0, min(b.x, b.y));
                float2 q = abs(p) - b + rx;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rx;
            }

            float sminPoly(float a, float b, float k) {
                float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
                return mix(b, a, h) - k * h * (1.0 - h);
            }

            // 斜面 / 法线 / 高光用透镜形状（平边已延伸出去，不产生边缘）
            float lensSDF(float2 p) {
                float d = sdRoundedBox4(p - shape1L.xy, shape1L.zw, radii1);
                if (shape2.z > 0.5) {
                    float d2 = sdRoundedBox(p - shape2.xy, shape2.zw, radius2);
                    if (blendK > 0.5) {
                        d = sminPoly(d, d2, blendK);
                    } else {
                        d = min(d, d2);
                    }
                }
                return d;
            }

            half4 main(float2 coord) {
                float2 p = coord - float2(margin, margin);
                // 视图矩形外硬切：平边方向形状延伸到了视图外，靠这一刀收口，
                // 平边上不做羽化，两块玻璃贴边拼接时不会叠出一条发丝缝
                if (p.x < 0.0 || p.y < 0.0 || p.x > viewSize.x || p.y > viewSize.y) {
                    return half4(0.0);
                }
                float d = lensSDF(p);

                // 覆盖率：1.5px 抗锯齿羽化，形状外完全透明
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
                if (cov <= 0.004) {
                    return half4(0.0);
                }

                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                // Deep inside the flat body, refraction and rim lighting are zero.
                // Skip four SDF evaluations here; animated edges keep the full model.
                bool flatInterior = t >= 1.0 && -d > max(rimBandMax + 3.0, 3.0);
                float2 n = float2(0.0, -1.0);
                if (!flatInterior) {
                    n = float2(
                        lensSDF(p + float2(1.0, 0.0)) - lensSDF(p - float2(1.0, 0.0)),
                        lensSDF(p + float2(0.0, 1.0)) - lensSDF(p - float2(0.0, 1.0))
                    );
                    float nLen = length(n);
                    if (nLen > 0.0001) {
                        n = n / nLen;
                    } else {
                        n = float2(0.0, -1.0);
                    }
                }

                float edge = 1.0 - t;
                float slope = 0.0;
                if (flatInterior) {
                    slope = 0.0;
                } else if (falloff > 0.001) {
                    // 引力透镜式逆幂衰减：位移 ∝ (1 + x/k)^-p，核半径 k = 斜面宽度的 1/4，
                    // 减去带末端的值再归一化，贴边 = 1、带末端平滑落到 0。越贴边越剧烈：
                    // p = 2 时离边 k 处只剩 1/4，绝大部分弯折压在最外几个像素，
                    // 内侧只留一段缓慢回落的轻微放大尾巴
                    float gB = pow(5.0, -falloff);
                    slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);
                } else {
                    // 平方斜面：弯折沿整条斜面带均匀铺开
                    slope = edge * edge;
                }

                // Expanded volume panels need a quiet lower edge. Blend through
                // the corners by normal direction, leaving the right edge intact.
                float bottomWeight = smoothstep(0.35, 0.95, n.y) * bottomEdgeSoftening;
                // No residual mirrored band on the lower horizontal edge.
                // Keeping even a small displacement compresses high-contrast
                // notification/text pixels into a visible horizontal stripe.
                slope *= 1.0 - bottomWeight;

                // 折射：refractDir = -1 沿法线向内采样（默认，与 iOS 一致）——边缘是内侧背景的
                // 压缩镜像；+1 为可选的凸透镜模式，向外采样，形状外的背景被弯进边缘，
                // 靠近的内容还没进到玻璃下面就先出现在边缘，进来之后沿边缘延展。
                // RuntimeShader 子输入只保证"输出裁剪区"内可采样（Android 未暴露 Skia 的
                // childSampleRadius），向外采样必须让输出区覆盖整个外扩录制区——见 draw()
                // 里的外层合成节点；采样再由 sampleLo/Hi 钳在有内容的范围内
                float refr = refractPx * (1.0 + 0.6 * press);
                float2 offset = n * (refractDir * slope * refr);

                // 触摸局部液态凸起（手指下方的局部放大：采样向触点收缩）
                if (touchAmp > 0.001) {
                    float2 tp = p - touchPos;
                    float tr = length(tp);
                    float sigma = max(max(shape1.z, shape1.w), 1.0);
                    float bump = touchAmp * exp(-(tr * tr) / (sigma * sigma * 0.30));
                    if (tr > 1.0) {
                        offset -= (tp / tr) * (bump * refr * 0.5);
                    }
                }

                // 色散：三通道折射量不同（蓝光弯折最多），边缘出现光谱边纹
                float2 cR = coord + offset * (1.0 - dispersion * slope);
                float2 cG = coord + offset;
                float2 cB = coord + offset * (1.0 + dispersion * slope);

                // 安全钳制到有内容的采样区，杜绝透明黑
                float2 lo = sampleLo;
                float2 hi = sampleHi;
                cR = clamp(cR, lo, hi);
                cG = clamp(cG, lo, hi);
                cB = clamp(cB, lo, hi);
                float3 col;
                if (slope == 0.0) {
                    // All chromatic coordinates coincide, including a touch bump.
                    col = content.eval(cG).rgb;
                } else if (rimSoft > 0.01 && slope > 0.001) {
                    // 边缘柔化：折射带内沿法线方向抹匀（宽度随斜面深度增长），
                    // 压缩带从一条硬线变成一段渐变，采样点仍钳在内容区内
                    float2 sm = n * (rimSoft * slope);
                    float2 cR1 = clamp(cR - sm, lo, hi);
                    float2 cR2 = clamp(cR + sm, lo, hi);
                    float2 cG1 = clamp(cG - sm, lo, hi);
                    float2 cG2 = clamp(cG + sm, lo, hi);
                    float2 cB1 = clamp(cB - sm, lo, hi);
                    float2 cB2 = clamp(cB + sm, lo, hi);
                    col = float3(
                        (content.eval(cR).r + content.eval(cR1).r + content.eval(cR2).r) / 3.0,
                        (content.eval(cG).g + content.eval(cG1).g + content.eval(cG2).g) / 3.0,
                        (content.eval(cB).b + content.eval(cB1).b + content.eval(cB2).b) / 3.0
                    );
                } else {
                    col = float3(
                        content.eval(cR).r,
                        content.eval(cG).g,
                        content.eval(cB).b
                    );
                }

                // 饱和度（合并进同一 pass）；提饱和端走 vibrancy 曲线：低饱和
                // 像素多提、高饱和像素少提、极亮像素保护，避免线性提饱和把浓色
                // 推过曝（曲线与 GlassRuntimeEffects 的 API 36 滤镜一致）
                float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
                if (satFactor <= 1.0) {
                    col = mix(float3(lum), col, satFactor);
                } else {
                    float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                    float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                    float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                    float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);
                    col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
                }

                // Measure transmission before tinting. SystemUI uses white foreground
                // symbols, so bright source pixels need additional attenuation even
                // in Clear. Other library callers opt in explicitly.
                float sourceLuma = dot(col, float3(0.2126, 0.7152, 0.0722));
                float brightBackdrop = smoothstep(0.45, 0.95, sourceLuma);
                // 自适应染色（Regular）/ 压暗层（Clear）
                if (adaptiveTint > 0.5) {
                    // 逐像素自适应：按局部（模糊后）亮度在提亮/压暗之间平滑过渡，
                    // 玻璃跨明暗背景时不再整体翻转；曲线与全局版本
                    // （LiquidGlassView.onLuminanceSample）一致
                    float lumT = dot(col, float3(0.2126, 0.7152, 0.0722));
                    float e = smoothstep(0.35, 0.75, lumT);
                    col = mix(col, float3(1.0 - e), (0.14 + 0.08 * e) * materialStrength);
                } else {
                    col = mix(col, tintColor.rgb, tintColor.a * materialStrength);
                }
                col = col * (1.0 - dimAmount);
                col *= 1.0 - highlightProtection * transmissionAttenuation * brightBackdrop * materialStrength;

                // 使用方指定的玻璃本体色：按"有色介质"建模——吸收（保留背景明暗
                // 层次与折射细节）+ 少量散射（暗背景下也看得出色相）。
                // 位置在光照之前：染色属于透射，镜面高光属于表面反射，不该被染色
                if (glassTint.a > 0.002) {
                    float lumTint = dot(col, float3(0.2126, 0.7152, 0.0722));
                    float3 absorbed = col * mix(float3(1.0), glassTint.rgb, 0.85);
                    float3 scattered = glassTint.rgb * (0.38 * (1.0 - lumTint));
                    col = mix(col, clamp(absorbed + scattered, float3(0.0), float3(1.0)), glassTint.a);
                }

                // 光照：同一法线场驱动。整圈亮边的明暗只由 dot(N, -L) 决定，没有与方向
                // 无关的常亮项——侧向（法线垂直于光线处）归零，不会留下一圈固定描边。
                // 两道对称的角度瓣（沿 iOS 26 控制中心截图里的控件一圈逐角量得）：迎光侧
                // 与背光侧峰值相等（背光侧是透明介质的内壁反射），瓣宽 pow 4.5（离轴 30°
                // 剩一半、45° 归零）；迎光侧另加一层向内的柔和辉光——iOS 左上角是亮线 +
                // 辉光，右下角只有亮线，两侧的边缘内侧都没有暗带
                float facing = dot(n, -lightDir);
                float lobeF = pow(max(facing, 0.0), 4.5);
                float lobeB = pow(max(-facing, 0.0), 4.5);

                // 贴边亮线（中心在边内 1px，半宽 2px）+ 迎光侧辉光：从亮线内侧（边内 3px）起
                // 向内 1.5 次幂衰减，宽度与斜面弱相关、上限默认 6px、小控件按短边收。
                // 辉光不叠在亮线上，两侧亮线峰值相等
                float bandW = clamp(bevel * 0.3, 2.0, rimBandMax);
                float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
                float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5) * cov;
                float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;
                float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF)
                             * specStrength * (1.0 - 0.35 * press);
                spec *= 1.0 - bottomWeight;
                // Reflect only into the remaining channel headroom. This keeps
                // the thin rim without clipping bright backgrounds to solid white.\n                float protectedSpec = spec * mix(1.0, 0.70, highlightProtection * brightBackdrop);
                col += float3(protectedSpec) * mix(float3(1.0), float3(1.0) - col, highlightProtection);

                col = clamp(col, float3(0.0), float3(1.0));
                return half4(half3(col * cov), half(cov));
            }
        """
}
