package com.rsaudio.formats.bridge;

import com.rsaudio.formats.FmLog;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;

/**
 * The only place in the mod that touches {@code com.rsaudio.*}.
 *
 * <p>Everything is resolved by name at runtime through {@link Loader}'s class loader: the rsaudio
 * classes are not a compile dependency (they live inside the hbmzz jar), and a class literal such as
 * {@code com.rsaudio.Config.class} would blow up during early loading or on a dedicated server. Every
 * entry point degrades to a documented "nothing happened" value plus one log line; nothing here throws
 * out to the caller.</p>
 *
 * <p>Server safety: rsaudio has no {@code clientSideOnly}, so its classes are present on a dedicated
 * server too, but the client-side ones must never be initialized there. Each public method bails out
 * unless {@link FMLCommonHandler#getSide()} is client.</p>
 */
public final class AudioBridge {

    private static final String MODID = "rsaudio";
    private static final String SOUND_DOMAIN = "rsaudio";

    private static final String CLS_CONFIG = "com.rsaudio.Config";
    private static final String CLS_AUDIO_LIBRARY = "com.rsaudio.client.AudioLibrary";
    private static final String CLS_ENTRY = "com.rsaudio.client.AudioLibrary$Entry";
    private static final String CLS_SOUND_PACK = "com.rsaudio.client.DynamicSoundPack";
    private static final String CLS_PACKET_HANDLER = "com.rsaudio.network.PacketHandler";
    private static final String CLS_PACKET_FINISHED = "com.rsaudio.network.PacketSoundFinished";

    private static final Map<String, Class<?>> CLASSES = new ConcurrentHashMap<String, Class<?>>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<String, Method>();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<String, Field>();

    private static volatile Boolean available;

    private AudioBridge() {
    }

    // ------------------------------------------------------------------ probes

    /** True when rsaudio is loaded and its key classes resolve on the client side. */
    public static boolean available() {
        if (!isClient()) {
            return false;
        }
        Boolean cached = available;
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean ok = false;
        try {
            ok = Loader.isModLoaded(MODID) && clazz(CLS_CONFIG) != null && clazz(CLS_AUDIO_LIBRARY) != null;
        } catch (Throwable t) {
            fail("available()", t);
        }
        available = Boolean.valueOf(ok);
        return ok;
    }

    /** rsaudio's audio folder ({@code config/rsaudio/audio}), or null when unavailable. */
    public static File audioDir() {
        if (!isClient()) {
            return null;
        }
        try {
            Class<?> type = clazz(CLS_CONFIG);
            if (type == null) {
                return null;
            }
            Method getter = method(type, "getAudioDir");
            if (getter == null) {
                return null;
            }
            Object value = getter.invoke(null);
            return value instanceof File ? (File) value : null;
        } catch (Throwable t) {
            fail("Config.getAudioDir()", t);
            return null;
        }
    }

    /**
     * The live {@code AudioLibrary.ENTRIES} list (the method really returns the backing ArrayList), so
     * adding to it is visible to rsaudio immediately. Read-only empty list when rsaudio is absent.
     */
    public static List<Object> entries() {
        List<Object> live = liveEntries();
        return live == null ? Collections.<Object>emptyList() : live;
    }

    /** {@code AudioLibrary.sanitize}, with a local copy of the same rule as the fallback. */
    public static String sanitize(String fileName) {
        if (fileName == null) {
            return "";
        }
        if (isClient()) {
            try {
                Class<?> type = clazz(CLS_AUDIO_LIBRARY);
                if (type != null) {
                    Method sanitize = method(type, "sanitize", String.class);
                    if (sanitize != null) {
                        Object value = sanitize.invoke(null, fileName);
                        if (value instanceof String) {
                            return (String) value;
                        }
                    }
                }
            } catch (Throwable t) {
                fail("AudioLibrary.sanitize()", t);
            }
        }
        return fallbackSanitize(fileName);
    }

    /** Builds an {@code AudioLibrary$Entry} (package-private constructor) and appends it to the library. */
    public static void inject(String soundName, String displayName, File file) {
        if (!isClient() || soundName == null || soundName.isEmpty() || file == null) {
            return;
        }
        try {
            // Never flag "scanned" ourselves: rsaudio only scans the folder inside getEntries() while
            // scanned == false. Marking it first made getEntries() hand back the still-empty list, and
            // rsaudio never scanned afterwards - the whole library stayed empty except our entries.
            Class<?> entryType = clazz(CLS_ENTRY);
            List<Object> list = liveEntries();
            if (entryType == null || list == null) {
                return;
            }
            Constructor<?> ctor = entryType.getDeclaredConstructor(String.class, String.class, File.class);
            ctor.setAccessible(true);
            Object entry = ctor.newInstance(soundName,
                    displayName == null || displayName.isEmpty() ? soundName : displayName, file);
            list.add(entry);
        } catch (Throwable t) {
            fail("inject(" + soundName + ")", t);
        }
    }

    public static boolean hasEntry(String soundName) {
        if (soundName == null) {
            return false;
        }
        List<Object> list = liveEntries();
        if (list == null) {
            return false;
        }
        for (Object entry : list) {
            if (soundName.equals(entryValue(entry, "soundName"))) {
                return true;
            }
        }
        return false;
    }

    /** True when some entry (other than {@code excludeSoundName}) already shows this display name. */
    public static boolean hasDisplayName(String displayName, String excludeSoundName) {
        if (displayName == null || displayName.isEmpty()) {
            return false;
        }
        List<Object> list = liveEntries();
        if (list == null) {
            return false;
        }
        for (Object entry : list) {
            Object shown = entryValue(entry, "displayName");
            Object sound = entryValue(entry, "soundName");
            if (!(shown instanceof String) || !((String) shown).equalsIgnoreCase(displayName)) {
                continue;
            }
            if (excludeSoundName != null && excludeSoundName.equals(sound)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Drops our entry for {@code soundName} (used when a converted ogg replaces the source file). */
    public static boolean remove(String soundName) {
        if (soundName == null) {
            return false;
        }
        List<Object> list = liveEntries();
        if (list == null) {
            return false;
        }
        try {
            boolean removed = false;
            for (Iterator<Object> it = list.iterator(); it.hasNext(); ) {
                Object entry = it.next();
                if (soundName.equals(entryValue(entry, "soundName"))) {
                    it.remove();
                    removed = true;
                }
            }
            return removed;
        } catch (Throwable t) {
            fail("remove(" + soundName + ")", t);
            return false;
        }
    }

    /** Library size read straight off the private static list field (never triggers rescan). */
    public static int entryCount() {
        try {
            Class<?> owner = clazz(CLS_AUDIO_LIBRARY);
            if (owner == null) {
                return -1;
            }
            java.lang.reflect.Field f = declaredField(owner, "ENTRIES");
            if (f == null) {
                return -1;
            }
            Object v = f.get(null);
            return v instanceof java.util.List ? ((java.util.List<?>) v).size() : -1;
        } catch (Throwable t2) {
            return -1;
        }
    }

    /** {@code AudioLibrary.rescan()}. Note: it clears the list, injected entries included. */
    public static void rescan() {
        if (!isClient()) {
            return;
        }
        try {
            Class<?> type = clazz(CLS_AUDIO_LIBRARY);
            if (type == null) {
                return;
            }
            Method rescan = method(type, "rescan");
            if (rescan != null) {
                rescan.invoke(null);
            }
        } catch (Throwable t) {
            fail("AudioLibrary.rescan()", t);
        }
    }

    /** {@code DynamicSoundPack.generate()}; returns whether the sounds.json actually changed. */
    public static boolean generate() {
        if (!isClient()) {
            return false;
        }
        try {
            Class<?> type = clazz(CLS_SOUND_PACK);
            if (type == null) {
                return false;
            }
            Method generate = method(type, "generate");
            if (generate == null) {
                return false;
            }
            Object value = generate.invoke(null);
            return value instanceof Boolean && ((Boolean) value).booleanValue();
        } catch (Throwable t) {
            fail("DynamicSoundPack.generate()", t);
            return false;
        }
    }

    /** {@code DynamicSoundPack.enableAndReload()} — a full client resource reload, call it once only. */
    public static void enableAndReload() {
        if (!isClient()) {
            return;
        }
        try {
            Class<?> type = clazz(CLS_SOUND_PACK);
            if (type == null) {
                return;
            }
            Method reload = method(type, "enableAndReload");
            if (reload != null) {
                reload.invoke(null);
            }
        } catch (Throwable t) {
            fail("DynamicSoundPack.enableAndReload()", t);
        }
    }

    /**
     * True when the vanilla sound engine can already play {@code rsaudio:<soundName>}, i.e. the entry
     * made it into the resource pack. Mirrors rsaudio's own {@code SoundHandler.getAccessor(...) != null}.
     */
    public static boolean nativeReady(String soundName) {
        if (!isClient() || soundName == null || soundName.isEmpty()) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return false;
            }
            SoundHandler handler = mc.getSoundHandler();
            if (handler == null) {
                return false;
            }
            return handler.getAccessor(new ResourceLocation(SOUND_DOMAIN, soundName)) != null;
        } catch (Throwable t) {
            fail("nativeReady(" + soundName + ")", t);
            return false;
        }
    }

    /** Announces "playback finished" to the server exactly the way rsaudio's own player does. */
    public static void sendFinished(BlockPos pos) {
        if (!isClient() || pos == null) {
            return;
        }
        try {
            Class<?> packetType = clazz(CLS_PACKET_FINISHED);
            Class<?> handlerType = clazz(CLS_PACKET_HANDLER);
            if (packetType == null || handlerType == null) {
                return;
            }
            Constructor<?> ctor = packetType.getConstructor(BlockPos.class);
            Object packet = ctor.newInstance(pos);
            Field instance = publicField(handlerType, "INSTANCE");
            if (instance == null) {
                return;
            }
            Object wrapper = instance.get(null);
            if (!(wrapper instanceof SimpleNetworkWrapper)) {
                fail("PacketHandler.INSTANCE", new IllegalStateException("unexpected field type"));
                return;
            }
            ((SimpleNetworkWrapper) wrapper).sendToServer((IMessage) packet);
        } catch (Throwable t) {
            fail("sendFinished(" + pos + ")", t);
        }
    }

    // ------------------------------------------------------------------ internals

    private static boolean isClient() {
        try {
            return FMLCommonHandler.instance().getSide().isClient();
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<Object> liveEntries() {
        if (!isClient()) {
            return null;
        }
        try {
            Class<?> type = clazz(CLS_AUDIO_LIBRARY);
            if (type == null) {
                return null;
            }
            Method getter = method(type, "getEntries");
            if (getter == null) {
                return null;
            }
            Object value = getter.invoke(null);
            if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) value;
                return list;
            }
            return null;
        } catch (Throwable t) {
            fail("AudioLibrary.getEntries()", t);
            return null;
        }
    }

    private static void markScanned() {
        Class<?> type = clazz(CLS_AUDIO_LIBRARY);
        if (type == null) {
            return;
        }
        Field scanned = declaredField(type, "scanned");
        if (scanned == null) {
            return;
        }
        try {
            scanned.setBoolean(null, true);
        } catch (Throwable t) {
            fail("AudioLibrary.scanned", t);
        }
    }

    private static Object entryValue(Object entry, String name) {
        if (entry == null) {
            return null;
        }
        Field field = publicField(entry.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            return field.get(entry);
        } catch (Throwable t) {
            fail("Entry." + name, t);
            return null;
        }
    }

    private static Class<?> clazz(String name) {
        Class<?> cached = CLASSES.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> type = Class.forName(name, false, Loader.class.getClassLoader());
            CLASSES.put(name, type);
            return type;
        } catch (Throwable t) {
            fail("class " + name, t);
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        String key = owner.getName() + "#" + name + "/" + params.length;
        Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Method found = owner.getMethod(name, params);
            METHODS.put(key, found);
            return found;
        } catch (Throwable t) {
            fail(key, t);
            return null;
        }
    }

    private static Field publicField(Class<?> owner, String name) {
        String key = owner.getName() + "." + name;
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Field found = owner.getField(name);
            FIELDS.put(key, found);
            return found;
        } catch (Throwable t) {
            fail(key, t);
            return null;
        }
    }

    private static Field declaredField(Class<?> owner, String name) {
        String key = owner.getName() + "#" + name;
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Field found = owner.getDeclaredField(name);
            found.setAccessible(true);
            FIELDS.put(key, found);
            return found;
        } catch (Throwable t) {
            fail(key, t);
            return null;
        }
    }

    /** One line per distinct failure site; the class name of the exception is always included. */
    private static void fail(String what, Throwable t) {
        Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        FmLog.warnOnce("bridge." + what, "AudioBridge." + what + " failed (" + FmLog.describe(cause) + ")");
    }

    private static String fallbackSanitize(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '/') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "").replace('/', '_');
        if (out.isEmpty()) {
            return "track";
        }
        if (out.matches("[0-9]+")) {
            return "track_" + out;
        }
        return out;
    }
}
