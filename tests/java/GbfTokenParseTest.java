import java.util.ArrayList;
import java.util.List;

import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.item.PaperToken;
import forge.model.FModel;

/**
 * Headless parse test for GBF token scripts (res/tokenscripts).
 *
 * <p>GbfParseTest only covers the [cards] of the GBF edition — token scripts
 * are resolved through TokenDb (StaticData) at token-creation time in a real
 * game, so a broken token script would not show up in GbfParseTest. This test
 * parses every GBF token through the SAME path a game uses:
 * {@code TokenDb.getToken(name, "GBF") -> PaperToken -> CardFactory.getCard(...)}
 * (the identical chain as VentureEffect / TokenInfo in a real game).
 *
 * <p>Usage: {@code java -Dfile.encoding=UTF-8 -cp "<install jar>;<classes>" GbfTokenParseTest [tokenScript...]}
 * (defaults to the 18 tokens registered in res/editions/Granblue Fantasy.txt [tokens]).
 * Run from the install dir; see GbfTestBase for the run rules.
 */
public class GbfTokenParseTest extends GbfTestBase {

    /** All 28 scripts registered in editions [tokens] (kept in sync with that section). */
    private static final String[] GBF_TOKENS = {
        "c_0_0_a_construct_flying_artifactcount",   // Mahira (GBF #126)
        "b_1_1_dog_ally_lifelink_deathtouch",       // Vajra (GBF #127)
        "b_1_2_dog_ally_lifelink",                  // Vajra (GBF #127)
        "rg_2_1_boar",                              // Kumbhira (GBF #128)
        "b_0_1_rat",                                // Vikala (GBF #129)
        "cerberus_enchantment",                     // Cerberus, Hadean Watchdog (GBF #55)
        "w_5_5_elemental_artifact_defender",        // batch-2 (GBF #161 Cosmos etc.)
        "u_2_1_human_knight",                       // batch-2
        "g_2_1_erune",                              // batch-2
        "r_4_3_bird_flying",                        // batch-2
        "g_3_4_dog_vigilance",                      // batch-2
        "w_2_1_spirit",                             // batch-2
        "b_1_5_construct_haste_lifelink",           // batch-2
        "u_2_1_spirit_flying",                      // batch-2
        "w_3_3_primal_flying_haste",                // batch-2
        "w_1_2_human_soldier_vigilance",            // batch-2
        "r_1_1_elemental_flying",                   // batch-2
        "b_2_2_primal_zombie",                      // batch-2
        "ub_2_1_erune_diesmill",                    // R46 (Tien/Feower 十天众)
        "rw_3_3_erune_vigilance",                   // R46 (Tien/Feower 十天众)
        "b_3_3_demon_flying",                       // R53 batch-3 Devilish Priest
        "b_1_1_bat",                                // R53 batch-3 Honorary Vow
        "c_2_2_a_construct_flying",                 // R53 batch-3 In Like a Capon
        "rw_2_2_knight_vigilance",                  // R53 batch-3 Lord's Strike
        "c_1_1_a_construct_floating",               // R53 batch-3 Luminiera Sword Omega
        "r_artifact_equipment_primal_sword",        // R53 batch-3 Warrior Wielding a Magic Sword
        "g_1_1_erune_monkey",                       // R53 batch-3 Water Monkey Splash
        "u_3_3_snow_reach_trample",                 // R53 batch-3 Uruki
    };

    public static void main(String[] args) {
        init();

        String[] tokens = args.length > 0 ? args : GBF_TOKENS;

        int ok = 0;
        List<String> fails = new ArrayList<>();
        for (String name : tokens) {
            try {
                PaperToken pt = FModel.getMagicDb().getAllTokens().getToken(name, "GBF");
                if (pt == null) {
                    fails.add(name + " (no paper token found for edition GBF)");
                    continue;
                }
                Card c = CardFactory.getCard(pt, null, 1, null);
                if (c == null) {
                    fails.add(name + " (null card)");
                    continue;
                }
                ok++;
                System.out.println("TOKEN OK: " + name + " -> " + c.getName()
                        + " | " + c.getType() + " | " + c.getNetPower() + "/" + c.getNetToughness()
                        + " | keywords=" + c.getKeywords());
            } catch (Throwable t) {
                fails.add(name + " -> " + t);
                t.printStackTrace(System.out);
            }
        }

        System.out.println("TOKEN RESULT: ok=" + ok + " fail=" + fails.size());
        for (String f : fails) {
            System.out.println("FAIL: " + f);
        }
        System.exit(fails.isEmpty() ? 0 : 1);
    }
}
