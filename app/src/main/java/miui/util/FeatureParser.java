package miui.util;

/** Defaults for Xiaomi hardware features that do not exist on other ROMs. */
public final class FeatureParser {
    public static boolean getBoolean(String name,boolean fallback){return fallback;}
    public static int getInteger(String name,int fallback){return fallback;}
    public static Float getFloat(String name,float fallback){return fallback;}
    public static int[] getIntArray(String name){return null;}
    public static String[] getStringArray(String name){return null;}
    public static String getString(String name){return null;}
    public static boolean hasFeature(String name,int type){return false;}
}
