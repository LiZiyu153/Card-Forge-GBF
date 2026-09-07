import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Headless regression test for Cerberus, Hadean Watchdog (GBF) after the
 * 0.0.1.8 fix:
 *
 * Original bug (as reported): the searched lands did not gain the "Primal"
 * and "Dog" creature types. Investigation showed the engine's
 * `DB$ Animate | Types$ Primal,Dog,Creature,Land` DOES add the subtypes
 * correctly; the real script flaw found was that DBAnimate used
 * `Defined$ Remembered`, and the remembered list also contained the two
 * creatures sacrificed for the ETB — so the sacrificed creatures got
 * animated too (wrong P/T/color/types on graveyard cards). Fixed by adding
 * `ForgetOtherRemembered$ True` to the library search, so ONLY the searched
 * lands remain remembered and get animated.
 *
 * Covered:
 *  1. After the search+animate chain, both searched lands are 1/1 black
 *     Primal Dog creature-lands (still lands, still their original land type).
 *  2. The two "sacrificed" creatures (simulated via remembered, sitting in
 *     the graveyard) are NOT animated: no Primal/Dog/Land types, P/T intact.
 */
public class GbfCerberusTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testSearchedLandsBecomePrimalDog();
        ok &= testSacrificedCreaturesNotAnimated();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /**
     * Drives the real scripted chain from the search onward: two creatures
     * are put on Cerberus' remembered list (as the ETB sacrifice would),
     * then DBSearchLands resolves (search 2 lands -> battlefield -> animate).
     */
    private static Card[] runSearchChain(Game game, Player p, Card cerberus,
            Card grunt1, Card grunt2) {
        cerberus.addRemembered(grunt1);
        cerberus.addRemembered(grunt2);

        SpellAbility search = AbilityFactory.getAbility(cerberus, "DBSearchLands");
        search.setActivatingPlayer(p);
        game.getStack().add(search);
        playUntilStackClear(game);

        // collect the two searched lands on the battlefield (Forests from the library)
        Card[] lands = new Card[2];
        int i = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && c.getType().hasCreatureType("Primal")) {
                lands[i++] = c;
            }
        }
        return lands;
    }

    /** Both searched lands: Primal + Dog creature types, still lands, 1/1, black. */
    private static boolean testSearchedLandsBecomePrimalDog() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card cerberus = makeCard("Cerberus, Hadean Watchdog", p, game);
        addToBattlefield(cerberus);
        Card grunt1 = makeCard("Grizzly Bears", p, game);
        Card grunt2 = makeCard("Grizzly Bears", p, game);
        addToBattlefield(grunt1);
        addToBattlefield(grunt2);
        for (int i = 0; i < 5; i++) {
            Card land = makeCard("Forest", p, game);
            p.getZone(ZoneType.Library).add(land);
        }

        Card[] lands = runSearchChain(game, p, cerberus, grunt1, grunt2);
        boolean ok = lands[0] != null && lands[1] != null;
        for (Card l : lands) {
            ok &= l != null
                    && l.isCreature() && l.isLand()
                    && l.getType().hasCreatureType("Primal")
                    && l.getType().hasCreatureType("Dog")
                    && l.getType().hasSubtype("Forest")
                    && l.getNetPower() == 1 && l.getNetToughness() == 1
                    && l.isBlack();
        }
        System.out.println("[Lands] found=" + (lands[0] != null && lands[1] != null)
                + " types0=" + (lands[0] != null ? lands[0].getType() : "null")
                + " types1=" + (lands[1] != null ? lands[1].getType() : "null")
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** The sacrificed creatures in the graveyard must NOT have been animated. */
    private static boolean testSacrificedCreaturesNotAnimated() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card cerberus = makeCard("Cerberus, Hadean Watchdog", p, game);
        addToBattlefield(cerberus);
        Card grunt1 = makeCard("Grizzly Bears", p, game);
        Card grunt2 = makeCard("Grizzly Bears", p, game);
        addToBattlefield(grunt1);
        addToBattlefield(grunt2);
        for (int i = 0; i < 5; i++) {
            Card land = makeCard("Forest", p, game);
            p.getZone(ZoneType.Library).add(land);
        }
        // sacrifice the grunts (they land in the graveyard like a real sacrifice)
        game.getAction().changeZone(null, grunt1.getController().getZone(ZoneType.Graveyard), grunt1, null, null);
        game.getAction().changeZone(null, grunt2.getController().getZone(ZoneType.Graveyard), grunt2, null, null);

        runSearchChain(game, p, cerberus, grunt1, grunt2);

        boolean grunt1Clean = !grunt1.getType().hasCreatureType("Primal")
                && !grunt1.getType().hasCreatureType("Dog")
                && !grunt1.isLand()
                && grunt1.getNetPower() == 2 && grunt1.getNetToughness() == 2;
        boolean grunt2Clean = !grunt2.getType().hasCreatureType("Primal")
                && !grunt2.getType().hasCreatureType("Dog")
                && !grunt2.isLand()
                && grunt2.getNetPower() == 2 && grunt2.getNetToughness() == 2;
        boolean ok = grunt1Clean && grunt2Clean;
        System.out.println("[Sacrificed] grunt1 types=" + grunt1.getType() + " pt=" + grunt1.getNetPower() + "/" + grunt1.getNetToughness()
                + " grunt2 types=" + grunt2.getType() + " pt=" + grunt2.getNetPower() + "/" + grunt2.getNetToughness()
                + " (must be untouched 2/2 Bears) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }
}
