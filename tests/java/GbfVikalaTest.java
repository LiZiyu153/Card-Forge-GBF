import forge.card.MagicColor;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Headless behavioral test for Vikala, Guardian of the North (GBF).
 *
 * 1. ETB: create a number of 0/1 black Rat tokens equal to the number of
 *    lands you control (real scripted TrigToken chain).
 * 2. "Whenever ANOTHER nontoken creature enters the battlefield, sacrifice
 *    CARDNAME" (real trigger path + scripted TrigSacSelf chain; Vikala's own
 *    entry must NOT trigger it).
 * 3. Death modal (DBMill option): sacrifice all Rats you control, remember
 *    them, then target player mills that many cards (X = Remembered$Amount);
 *    the mana mode's amount is half X rounded up (XHalf).
 *
 * Dev-environment notes: a self-leaving death trigger (Origin$ Battlefield)
 * is not queued by moveTo here, and Charm modes are not selectable headless,
 * so the death chain is exercised by driving the scripted option chains
 * directly (GbfArrietTest pattern). The Charm/choice interaction itself
 * needs a real game to verify.
 */
public class GbfVikalaTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testETBTokens();
        ok &= testSacSelfExcludesSelf();
        ok &= testSacSelfChain();
        ok &= testSacSelfTriggerRealPath();
        ok &= testDeathMillOptionChain();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    private static int countRatsOnBattlefield(Player p) {
        int n = 0;
        for (Card c : p.getZone(ZoneType.Battlefield).getCards()) {
            if (c.isToken() && c.getType().hasCreatureType("Rat")) {
                n++;
            }
        }
        return n;
    }

    /**
     * Drives the scripted TrigToken chain: with 3 lands you control, the ETB
     * must create exactly 3 0/1 black Rat tokens.
     */
    private static boolean testETBTokens() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);

        for (int i = 0; i < 3; i++) {
            addToBattlefield(makeCard("Swamp", p, game));
        }

        Card vikala = makeCard("Vikala, Guardian of the North", p, game);
        game.getAction().changeZone(null, p.getZone(ZoneType.Battlefield), vikala, null, null);
        game.getTriggerHandler().registerActiveTrigger(vikala, false);

        SpellAbility trig = AbilityFactory.getAbility(vikala, "TrigToken");
        trig.setActivatingPlayer(p);
        game.getStack().add(trig);
        playUntilStackClear(game);

        int rats = countRatsOnBattlefield(p);
        Card rat = null;
        for (Card c : p.getZone(ZoneType.Battlefield).getCards()) {
            if (c.isToken() && c.getType().hasCreatureType("Rat")) {
                rat = c;
                break;
            }
        }
        boolean okRats = rats == 3;
        boolean okStats = rat != null && rat.getNetPower() == 0 && rat.getNetToughness() == 1
                && rat.getColor().hasAnyColor(MagicColor.BLACK);
        System.out.println("[ETB] rats=" + rats + " (expect 3), rat 0/1 black=" + okStats
                + " -> " + (okRats && okStats ? "PASS" : "FAIL"));
        return okRats && okStats;
    }

    /**
     * The sacrifice trigger excludes Vikala herself: entering via the real
     * trigger path (with the ETB firing) must NOT kill her — only ANOTHER
     * nontoken creature entering does.
     */
    private static boolean testSacSelfExcludesSelf() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);

        Card vikala = makeCard("Vikala, Guardian of the North", p, game);
        game.getTriggerHandler().registerActiveTrigger(vikala, false);
        if (game.getZoneOf(vikala) == null) {
            vikala.getController().getZone(ZoneType.Hand).add(vikala);
        }
        game.getAction().moveTo(vikala.getController().getZone(ZoneType.Battlefield), vikala, null);
        game.getTriggerHandler().runWaitingTriggers();
        playUntilStackClear(game);

        boolean alive = vikala.getZone().getZoneType() == ZoneType.Battlefield;
        System.out.println("[SacSelfExcl] vikala in " + vikala.getZone().getZoneType()
                + " (expect Battlefield; own entry must not sacrifice her)"
                + " -> " + (alive ? "PASS" : "FAIL"));
        return alive;
    }

    /**
     * Drives the scripted TrigSacSelf chain: Vikala on the battlefield is
     * sacrificed.
     */
    private static boolean testSacSelfChain() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);

        Card vikala = makeCard("Vikala, Guardian of the North", p, game);
        game.getAction().changeZone(null, p.getZone(ZoneType.Battlefield), vikala, null, null);
        game.getTriggerHandler().registerActiveTrigger(vikala, false);

        SpellAbility sac = AbilityFactory.getAbility(vikala, "TrigSacSelf");
        sac.setActivatingPlayer(p);
        game.getStack().add(sac);
        playUntilStackClear(game);

        boolean ok = vikala.getZone().getZoneType() == ZoneType.Graveyard;
        System.out.println("[SacSelf] vikala in " + vikala.getZone().getZoneType() + " (expect Graveyard)"
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /**
     * Real trigger path: while Vikala is on the battlefield, ANOTHER nontoken
     * creature entering under any controller triggers "sacrifice CARDNAME"
     * (the trigger fires and Vikala ends up in the graveyard; in dev mode the
     * trigger resolves without leaving a stack entry, so only the outcome is
     * asserted).
     */
    private static boolean testSacSelfTriggerRealPath() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);

        Card vikala = makeCard("Vikala, Guardian of the North", p, game);
        game.getAction().changeZone(null, p.getZone(ZoneType.Battlefield), vikala, null, null);
        game.getTriggerHandler().registerActiveTrigger(vikala, false);

        boolean aliveBefore = vikala.getZone().getZoneType() == ZoneType.Battlefield;

        Card bear = makeCard("Grizzly Bears", p, game);
        if (game.getZoneOf(bear) == null) {
            bear.getController().getZone(ZoneType.Hand).add(bear);
        }
        game.getAction().moveTo(bear.getController().getZone(ZoneType.Battlefield), bear, null);
        game.getTriggerHandler().runWaitingTriggers();
        playUntilStackClear(game);

        boolean dead = vikala.getZone().getZoneType() == ZoneType.Graveyard;
        System.out.println("[SacSelfReal] aliveBefore=" + aliveBefore + " vikala in "
                + vikala.getZone().getZoneType() + " (expect Graveyard)"
                + " -> " + (aliveBefore && dead ? "PASS" : "FAIL"));
        return aliveBefore && dead;
    }

    /**
     * Death modal, mill mode: drive the scripted DBMill option chain with
     * 2 Rat tokens on the battlefield and 3 cards in the target's library.
     * Expect: both Rats sacrificed and remembered, target player mills 2.
     * Also verifies the mana-mode amount math: XHalf = ceil(X/2) = 1 for X=2.
     */
    private static boolean testDeathMillOptionChain() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        for (int i = 0; i < 2; i++) {
            addToBattlefield(makeCard("Swamp", p, game));
        }

        Card vikala = makeCard("Vikala, Guardian of the North", p, game);
        game.getAction().changeZone(null, p.getZone(ZoneType.Battlefield), vikala, null, null);
        game.getTriggerHandler().registerActiveTrigger(vikala, false);

        // create the 2 Rat tokens via the real scripted ETB chain
        SpellAbility trig = AbilityFactory.getAbility(vikala, "TrigToken");
        trig.setActivatingPlayer(p);
        game.getStack().add(trig);
        playUntilStackClear(game);
        int ratsBefore = countRatsOnBattlefield(p);
        if (ratsBefore != 2) {
            System.out.println("[DeathMill] setup failed: rats=" + ratsBefore + " (expect 2) -> FAIL");
            return false;
        }

        // 3 cards in EACH player's library so the mill amount is checkable
        // whichever player the AI picks as the mill target
        for (Player pl : new Player[] { p, q }) {
            for (int i = 0; i < 3; i++) {
                Card f = makeCard("Forest", pl, game);
                game.getAction().changeZone(null, pl.getZone(ZoneType.Library), f, null, null);
            }
        }

        // drive the scripted mill option: SacrificeAll -> Mill (target chosen by AI)
        SpellAbility mill = AbilityFactory.getAbility(vikala, "DBMill");
        mill.setActivatingPlayer(p);
        if (!mill.setupTargets()) {
            System.out.println("[DeathMill] setupTargets failed -> FAIL");
            return false;
        }
        Player millTarget = mill.getSubAbility().getTargets().getTargetPlayers().iterator().next();
        int libBefore = millTarget.getZone(ZoneType.Library).size();
        game.getStack().add(mill);
        playUntilStackClear(game);

        int libAfter = millTarget.getZone(ZoneType.Library).size();
        int ratsAfter = countRatsOnBattlefield(p);
        int remembered = vikala.getRememberedCount();
        int xHalf = AbilityUtils.calculateAmount(vikala, "XHalf", mill);

        boolean okSac = ratsAfter == 0;
        boolean okRem = remembered == 2;
        boolean okMill = libBefore - libAfter == 2;
        boolean okHalf = xHalf == 1;
        System.out.println("[DeathMill] ratsBefore=" + ratsBefore + " ratsAfter=" + ratsAfter
                + " remembered=" + remembered + " target=" + millTarget
                + " libBefore=" + libBefore + " libAfter=" + libAfter + " milled=" + (libBefore - libAfter)
                + " XHalf=" + xHalf + " (expect 1)"
                + " -> " + (okSac && okRem && okMill && okHalf ? "PASS" : "FAIL"));
        return okSac && okRem && okMill && okHalf;
    }
}
