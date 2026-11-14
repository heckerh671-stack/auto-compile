package dev.zalard.spoof;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cape Spoof: every official Java cape, by its texture on Mojang's texture server
 * (textures.minecraft.net/texture/&lt;hash&gt;). The PNG is downloaded once, kept in
 * .minecraft/mako/capes, and only ever drawn on your own screen.
 */
public final class Capes {
    private Capes() {}

    public static final Map<String, String> ALL = new LinkedHashMap<>();
    static {
        ALL.put("Migrator",       "2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933");
        ALL.put("Vanilla",        "f9a76537647989f9a0b6d001e320dac591c359e9e61a31f4ce11c88f207f0ad4");
        ALL.put("Experience",     "7658c5025c77cfac7574aab3af94a46a8886e3b7722a895255fbf22ab8652434");
        ALL.put("Pan",            "28de4a81688ad18b49e735a273e086c18f1e3966956123ccb574034c06f5d336");
        ALL.put("Common",         "5ec930cdd2629c8771655c60eebeb867b4b6559b0e6d3bc71c40c96347fa03f0");
        ALL.put("15th Anniv",     "cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625");
        ALL.put("Cherry",         "afd553b39358a24edfe3b8a9a939fa5fa4faa4d9a9c3d6af8eafb377fa05c2bb");
        ALL.put("Purple Heart",   "cb40a92e32b57fd732a00fc325e7afb00a7ca74936ad50d8e860152e482cfbde");
        ALL.put("Follower's",     "569b7f2a1d00d26f30efe3f9ab9ac817b1e6d35f4f3cfb0324ef2d328223d350");
        ALL.put("Copper",         "5e6f3193e74cd16cdd6637d9bae5484e3a37ff2a14c2d157c659a07810b1bdca");
        ALL.put("Mojang Office",  "5c29410057e32abec02d870ecb52ec25fb45ea81e785a7854ae8429d7236ca26");
        ALL.put("MCC 15th",       "56c35628fe1c4d59dd52561a3d03bfa4e1a76d397c8b9c476c2f77cb6aebb1df");
        ALL.put("Menace",         "dbc21e222528e30dc88445314f7be6ff12d3aeebc3c192054fba7e3b3f8c77b1");
        ALL.put("Home",           "1de21419009db483900da6298a1e6cbf9f1bc1523a0dcdc16263fab150693edd");
        ALL.put("Yearn",          "308b32a9e303155a0b4262f9e5483ad4a22e3412e84fe8385a0bdd73dc41fa89");
        ALL.put("Crafter",        "479eacefa3cdd7aca94207f36c0dd449653ddf259daf40544a5866baf05eee22");
        ALL.put("Builder",        "2c579968c64c1719740fd8c2a451461879b238002574fce48f7d1a7c36a1c7d4");
        ALL.put("Moonlight",      "fe8a02dfe9e390e44ff33d69feef9d3943f76d3901015bbd50f0b67722d288bd");
        ALL.put("Twisted",        "24aafc451aa2cc34ddc7265211678585c0ef4da4d32edb75ecec1bd8b5408381");
        ALL.put("Hero",           "bb384a1079b9a6f2811520c7991b6e8150d02e5a96457e44509e30822f72f38e");
        ALL.put("Aurora",         "7c418dfbd37412a55e9f1425c9807a591ad10cf71b1edb576a587be9114c277f");
        ALL.put("Zombie Horse",   "a3f6e4f14801f3ea55e3d95b9b4ef3b5e8802d947f669de93d6ec4b9354a436b");
        ALL.put("Founder's",      "99aba02ef05ec6aa4d42db8ee43796d6cd50e4b2954ab29f0caeb85f96bf52a1");
        ALL.put("Minecon 2011",   "953cac8b779fe41383e675ee2b86071a71658f2180f56fbce8aa315ea70e2ed6");
        ALL.put("Minecon 2012",   "a2e8d97ec79100e90a75d369d1b3ba81273c4f82bc1b737e934eed4a854be1b6");
        ALL.put("Minecon 2013",   "153b1a0dfcbae953cdeb6f2c2bf6bf79943239b1372780da44bcbb29273131da");
        ALL.put("Minecon 2015",   "b0cc08840700447322d953a02b965f1d65a13a603bf64b17c803c21446fe1635");
        ALL.put("Minecon 2016",   "e7dfea16dc83c97df01a12fabbd1216359c0cd0ea42f9999b6e97c584963e980");
        ALL.put("Mojang Classic", "8f120319222a9f4a104e2f5cb97b2cda93199a2ee9e1585cb8d09d6f687cb761");
        ALL.put("Mojang",         "9e507afc56359978a3eb3e32367042b853cddd0995d17d0da995662913fb00f7");
        ALL.put("Mojang Studios", "5786fe99be377dfb6858859f926c4dbc995751e91cee373468c5fbf4865e7151");
        ALL.put("Mapmaker",       "17912790ff164b93196f08ba71d0e62129304776d0f347334f8a6eae509f8a56");
        ALL.put("Translator",     "1bf91499701404e21bd46b0191d63239a4ef76ebde88d27e4d430ac211df681e");
        ALL.put("Translator CN",  "2262fb1d24912209490586ecae98aca8500df3eff91f2a07da37ee524e7e3cb6");
        ALL.put("Translator JP",  "ca29f5dd9e94fb1748203b92e36b66fda80750c87ebc18d6eafdb0e28cc1d05f");
        ALL.put("Moderator",      "ae677f7d98ac70a533713518416df4452fe5700365c09cf45d0d156ea9396551");
        ALL.put("Cobalt",         "ca35c56efe71ed290385f4ab5346a1826b546a54d519e6a3ff01efa01acce81");
        ALL.put("Scrolls",        "3efadf6510961830f9fcc077f19b4daf286d502b5f5aafbd807c7bbffcaca245");
        ALL.put("Turtle",         "5048ea61566353397247d2b7d946034de926b997d5e66c86483dfb1e031aee95");
        ALL.put("Prismarine",     "d8f8d13a1adf9636a16c31d47f3ecc9bb8d8533108aa5ad2a01b13b1a0c55eac");
        ALL.put("Birthday",       "2056f2eebd759cce93460907186ef44e9192954ae12b227d817eb4b55627a7fc");
        ALL.put("Valentine",      "6a7cf0eb5cfe7e7c508b364e32916dfd28d164e7bf6d92c6ea7811b82451e760");
        ALL.put("Bacon",          "fd14214cd8073059e93d9c626260f5df85e5a959181537119df56cadaf5002cc");
        ALL.put("dB",             "bcfbe84c6542a4a5c213c1cacf8979b5e913dcb4ad783a8b80e3c4a7d5c8bdac");
        ALL.put("Document",       "23917a9b79ac95602bba0027c2d3b4a67f22ca537e8c984400c48f8056c904eb");
        ALL.put("Oxeye",          "7706b5f5fc90329691e59277dcc66ba20572219fa8e5da472afd5235fad12cc8");
        ALL.put("Millionth Sale", "70efffaf86fe5bc089608d3cb297d3e276b9eb7a8f9f2fe6659c23a2d8b18edf");
        ALL.put("JulianClark",    "23ec737f18bfe4b547c95935fc297dd767bb84ee55bfd855144d279ac9bfd9fe");
        ALL.put("MrMessiah",      "2e002d5e1758e79ba51d08d92a0f3a95119f2f435ae7704916507b6c565a7da8");
    }
    public static List<String> names() { return List.copyOf(ALL.keySet()); }

    private static final Map<String, byte[]> PNG = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> BUSY = new ConcurrentHashMap<>();
    private static volatile String lastError = "";
    public static String lastError() { return lastError; }

    /** The cape's PNG (memory, then disk, then Mojang's texture server on a worker thread). */
    public static void fetch(String name, java.util.function.BiConsumer<String, byte[]> onReady, java.util.function.Consumer<String> onFail) {
        String hash = ALL.get(name);
        if (hash == null) { onFail.accept("unknown cape"); return; }
        byte[] hit = PNG.get(hash);
        if (hit != null) { onReady.accept(hash, hit); return; }
        if (BUSY.putIfAbsent(hash, true) != null) return;
        Thread t = new Thread(() -> {
            try {
                var file = java.nio.file.Path.of(System.getProperty("user.dir"), "mako", "capes", hash + ".png");
                byte[] png = null;
                if (java.nio.file.Files.exists(file)) png = java.nio.file.Files.readAllBytes(file);
                if (png == null) {
                    HttpURLConnection c = (HttpURLConnection) URI.create("https://textures.minecraft.net/texture/" + hash).toURL().openConnection();
                    c.setConnectTimeout(5000); c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    if (c.getResponseCode() != 200) { lastError = "texture server said " + c.getResponseCode(); onFail.accept(lastError); return; }
                    try (var in = c.getInputStream()) { png = in.readAllBytes(); }
                    try { java.nio.file.Files.createDirectories(file.getParent()); java.nio.file.Files.write(file, png); } catch (Exception ignored) {}
                }
                PNG.put(hash, png);
                onReady.accept(hash, png);
            } catch (Exception e) {
                lastError = e.toString(); onFail.accept(lastError);
            } finally { BUSY.remove(hash); }
        }, "mako-cape-fetch");
        t.setDaemon(true);
        t.start();
    }
}
