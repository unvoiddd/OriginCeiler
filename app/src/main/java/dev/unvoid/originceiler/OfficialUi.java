package dev.unvoid.originceiler;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;

/** Private resource namespace: official 0x7f IDs never enter the app's Resources. */
final class OfficialUi extends ContextWrapper implements AutoCloseable {
    private final Resources resources;
    private final Resources.Theme theme;
    private final ClassLoader loader;
    private LayoutInflater inflater;

    OfficialUi(Context base) throws Exception {
        this(base, false);
    }

    @SuppressWarnings("deprecation")
    OfficialUi(Context base, boolean forceDark) throws Exception {
        super(base);
        File file = LockscreenClockRuntime.flymeUi(base);
        ClassLoader dexLoader = null;
        try {
            dexLoader = new dalvik.system.DexClassLoader(
                file.getPath(),
                base.getCodeCacheDir().getPath(),
                null,
                base.getClassLoader()
            );
        } catch (Throwable t) {
            android.util.Log.e("OfficialUi", "Failed to create DexClassLoader for editor-ui.apk", t);
        }
        loader = dexLoader != null ? dexLoader : base.getClassLoader();

        ApplicationInfo info = new ApplicationInfo();
        info.packageName = "com.flyme.systemuieditor";
        info.uid = android.os.Process.myUid();
        info.targetSdkVersion = base.getApplicationInfo().targetSdkVersion;
        info.sourceDir = file.getPath();
        info.publicSourceDir = file.getPath();
        info.splitSourceDirs = null;
        info.splitPublicSourceDirs = null;
        info.sharedLibraryFiles = null;

        Resources source = null;
        try {
            source = base.getPackageManager().getResourcesForApplication(info);
            if (source.getIdentifier("layout_distinctive_self", "layout", "com.flyme.systemuieditor") == 0) {
                source = null;
            }
        } catch (Throwable ignored) {
            source = null;
        }
        if (source == null) {
            AssetManager assets = AssetManager.class.getConstructor().newInstance();
            AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, file.getPath());
            source = new Resources(assets, base.getResources().getDisplayMetrics(), base.getResources().getConfiguration());
        }
        resources = source;

        if (forceDark) {
            Configuration config = new Configuration(source.getConfiguration());
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
            resources.updateConfiguration(config, source.getDisplayMetrics());
        }
        theme = resources.newTheme();
        theme.applyStyle(id("style", "Theme.EditorActivity"), true);
    }

    int id(String type, String name) {
        int id = resources.getIdentifier(name, type, "com.flyme.systemuieditor");
        if (id == 0) throw new IllegalArgumentException(type + "/" + name);
        return id;
    }

    View inflate(String name, ViewGroup parent) {
        return ((LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE)).inflate(id("layout", name), parent, false);
    }

    View find(View root, String name) {
        return root.findViewById(id("id", name));
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
    public ClassLoader getClassLoader() {
        return loader;
    }

    @Override
    public Resources.Theme getTheme() {
        return theme;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Object getSystemService(String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (inflater == null) {
                inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
                inflater.setFactory2(new LayoutInflater.Factory2() {
                    @Override
                    public View onCreateView(View parent, String name, Context context, android.util.AttributeSet attrs) {
                        return onCreateView(name, context, attrs);
                    }

                    @Override
                    public View onCreateView(String name, Context context, android.util.AttributeSet attrs) {
                        if (name.contains(".")) {
                            try {
                                Class<?> clazz = loader.loadClass(name);
                                return (View) clazz.getConstructor(Context.class, android.util.AttributeSet.class).newInstance(context, attrs);
                            } catch (Throwable ignored) {
                            }
                        }
                        return null;
                    }
                });
            }
            return inflater;
        }
        return super.getSystemService(name);
    }

    @Override
    public void close() {
        inflater = null;
    }
}
