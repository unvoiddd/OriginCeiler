package dev.unvoid.originceiler;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.LayoutInflater;
import dalvik.system.DexClassLoader;
import java.io.File;

/** Private original HyperOS runtime; only Android framework classes are shared. */
final class OfficialHyperOsUi extends ContextWrapper {
    private static ClassLoader sharedLoader;
    private static String sharedPath;
    private static Class<?> fontBridge;
    private static Class<?> looperBridge;
    private static ClassLoader platformBridge;
    private final Resources resources;
    private final Resources.Theme theme;
    private final ClassLoader loader;
    private android.graphics.Typeface neumatic;
    private LayoutInflater inflater;

    OfficialHyperOsUi(Context host, File protectedApk) throws Exception {
        this(host, protectedApk, false);
    }

    private OfficialHyperOsUi(Context host, File protectedApk, boolean superWallpaper) throws Exception {
        super(host);
        fontBridge(host).getMethod("configure", String.class).invoke(null, LockscreenClockRuntime.globalFont(host).getPath());
        loader = loader(host, protectedApk);
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = "com.miui.aod";
        info.uid = android.os.Process.myUid();
        info.sourceDir = protectedApk.getPath();
        info.publicSourceDir = protectedApk.getPath();
        Resources source;
        try {
            source = host.getPackageManager().getResourcesForApplication(info);
        } catch (Throwable t) {
            try {
                AssetManager assets = AssetManager.class.getConstructor().newInstance();
                AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, protectedApk.getPath());
                source = new Resources(assets, host.getResources().getDisplayMetrics(), host.getResources().getConfiguration());
            } catch (Throwable t2) {
                throw new IllegalStateException("Failed to load HyperOS resources", t);
            }
        }

        if (superWallpaper) {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            metrics.setTo(source.getDisplayMetrics());
            android.content.res.Configuration configuration = new android.content.res.Configuration(source.getConfiguration());
            int dpi = XiaomiAodDensity.resolve(host, loader);
            metrics.densityDpi = dpi;
            metrics.density = dpi / 160f;
            metrics.scaledDensity = metrics.density * configuration.fontScale;
            configuration.densityDpi = dpi;
            resources = new Resources(source.getAssets(), metrics, configuration);
        } else {
            resources = source;
        }
        theme = resources.newTheme();
        theme.applyStyle(android.R.style.Theme_Material_NoActionBar, true);

        loadOriginalSystemFonts(host);
    }

    private static synchronized Class<?> fontBridge(Context host) throws Exception {
        if (fontBridge == null) {
            ClassLoader module = OfficialHyperOsUi.class.getClassLoader();
            try {
                Class<?> candidate = module.loadClass("miui.util.font.MultiLangHelper");
                candidate.getMethod("configure", String.class);
                fontBridge = candidate;
                looperBridge = module.loadClass("com.miui.aod.utils.LooperUtils");
                platformBridge = module;
                return fontBridge;
            } catch (ClassNotFoundException | NoSuchMethodException nativeMiuiClass) {
            }

            String moduleApk = host.getPackageManager().getApplicationInfo("dev.unvoid.originceiler", 0).sourceDir;
            ClassLoader bridge = new DexClassLoader(moduleApk, host.getCodeCacheDir().getPath(), null, Context.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals("miui.util.font.MultiLangHelper") || name.equals("com.miui.aod.utils.LooperUtils")) {
                        Class<?> c = findLoadedClass(name);
                        return c == null ? findClass(name) : c;
                    }
                    return super.loadClass(name, resolve);
                }
            };
            fontBridge = bridge.loadClass("miui.util.font.MultiLangHelper");
            looperBridge = bridge.loadClass("com.miui.aod.utils.LooperUtils");
            platformBridge = bridge;
        }
        return fontBridge;
    }

    private static synchronized ClassLoader loader(Context host, File apk) throws Exception {
        String path = apk.getAbsolutePath();
        if (sharedLoader == null || !path.equals(sharedPath)) {
            ClassLoader dependencies = new ClassLoader(Context.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals("miui.util.font.MultiLangHelper")) return fontBridge;
                    if (name.equals("com.miui.aod.utils.LooperUtils")) return looperBridge;
                    try {
                        return super.loadClass(name, resolve);
                    } catch (ClassNotFoundException missing) {
                        if (name.equals("miui.util.FeatureParser") || name.equals("miui.os.Build")) {
                            return platformBridge.loadClass(name);
                        }
                        throw missing;
                    }
                }
            };
            sharedLoader = new DexClassLoader(path, host.getCodeCacheDir().getPath(), null, dependencies);
            sharedPath = path;
        }
        return sharedLoader;
    }

    static OfficialHyperOsUi open(Context host) throws Exception {
        return new OfficialHyperOsUi(host, LockscreenClockRuntime.unpack(host, "hyperos"));
    }

    static OfficialHyperOsUi openSuperWallpaper(Context host) throws Exception {
        return new OfficialHyperOsUi(host, LockscreenClockRuntime.unpack(host, "hyperos"), true);
    }

    @SuppressWarnings("unchecked")
    private void loadOriginalSystemFonts(Context host) throws Exception {
        File neumaticFile = ClockFontManager.getFontFile(host, "native-clock/runtime/hyperos/fonts/NeumaticCompressed.otf");
        neumatic = new android.graphics.Typeface.Builder(neumaticFile).build();
        if (neumatic == null) throw new IllegalStateException("Original Neumatic font missing");
        Class<?> fonts = loader.loadClass("com.miui.clock.utils.FontUtils");
        java.util.Map<String, android.graphics.Typeface> cache = (java.util.Map<String, android.graphics.Typeface>)
                fonts.getField("mAllInOneFontCache").get(null);
        java.util.Map<String, String> names = (java.util.Map<String, String>) fonts.getField("ALLINONE_FONT_FILE_MAP").get(null);
        for (java.util.Map.Entry<String, String> entry : names.entrySet()) {
            if (cache.containsKey(entry.getKey())) continue;
            File f = ClockFontManager.getFontFile(host, "native-clock/runtime/hyperos/fonts/" + entry.getValue());
            android.graphics.Typeface face = new android.graphics.Typeface.Builder(f).build();
            if (face == null) throw new IllegalStateException("Original HyperOS clock font missing: " + entry.getValue());
            cache.put(entry.getKey(), face);
        }
        java.util.Map<String, android.graphics.Typeface> other = (java.util.Map<String, android.graphics.Typeface>)
                fonts.getField("mOtherFontTypefaceMap").get(null);
        if (fonts.getMethod("getMiSansGlobal").invoke(null) == null) {
            throw new IllegalStateException("Original MiSans global font missing");
        }
        for (String locale : new String[]{"SC", "TC"}) {
            String key = "miclock-miserif-" + locale.toLowerCase(java.util.Locale.ROOT) + "-vf";
            if (other.containsKey(key)) continue;
            File f = ClockFontManager.getFontFile(host, "native-clock/runtime/hyperos/fonts/MiSerif" + locale + "VF.ttf");
            android.graphics.Typeface face = new android.graphics.Typeface.Builder(f).build();
            if (face == null) throw new IllegalStateException("Original HyperOS Chinese clock font missing");
            other.put(key, face);
        }
        String[][] aesthetics = {
                {"miclock-commuters-sans-mono", "CommutersSansMono.otf"},
                {"miclock-bebas-neue-mono", "BebasNeue-Mono.otf"}
        };
        for (String[] font : aesthetics) {
            if (other.containsKey(font[0])) continue;
            File f = ClockFontManager.getFontFile(host, "native-clock/runtime/hyperos/fonts/" + font[1]);
            android.graphics.Typeface face = new android.graphics.Typeface.Builder(f).build();
            if (face == null) throw new IllegalStateException("Original HyperOS aesthetics font missing: " + font[1]);
            other.put(font[0], face);
        }
    }

    android.graphics.Typeface originalSystemFamily(String family) {
        return "miclock-neue-matic-compressed-black".equals(family) ? neumatic : null;
    }

    @Override
    public Resources getResources() {
        return resources;
    }

    @Override
    public AssetManager getAssets() {
        return resources.getAssets();
    }

    @Override
    public Resources.Theme getTheme() {
        return theme;
    }

    @Override
    public ClassLoader getClassLoader() {
        return loader;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Object getSystemService(String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (inflater == null) inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
            return inflater;
        }
        return super.getSystemService(name);
    }
}
