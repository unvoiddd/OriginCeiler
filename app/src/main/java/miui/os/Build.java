package miui.os;

/** Device identity for unmodified Xiaomi UI when no MIUI framework is installed. */
public final class Build {
    public static final String DEVICE=android.os.Build.DEVICE;
    public static final boolean IS_INTERNATIONAL_BUILD=!"CN".equals(java.util.Locale.getDefault().getCountry());
    public static final boolean IS_STABLE_VERSION=true;
    public static final boolean IS_TABLET=android.content.res.Resources.getSystem().getConfiguration().smallestScreenWidthDp>=600;
    public static String getRegion(){return java.util.Locale.getDefault().getCountry();}
}
