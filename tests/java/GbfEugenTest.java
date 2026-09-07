import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Headless regression test for Eugen, Loyalist of Awakened Blue (GBF) after the
 * 0.0.1.6 rebalance: PT 2/1 -> 2/2 and the trigger changed from
 * "before combat damage is dealt, for each attacking creature you control" to
 * "at the beginning of the combat phase of each turn".
 *
 * Covers:
 *  1. TrigBeginCombat chain: 1 damage to target creature an opponent controls.
 *  2. Fallback: with no opponent creatures, 1 damage to the opponent player.
 *  3. End-to-end phase trigger: fires at Begin Combat on BOTH your turn and the
 *     opponent's turn (Phase$ BeginCombat | ValidPlayer$ Player), and the
 *     no-creature fallback deals 1 to the opponent each time.
 */
public class GbfEugenTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testCreatureDamage();
        ok &= testFallbackPlayerDamage();
        ok &= testFiresOnBothPlayersCombat();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /** Drive TrigBeginCombat with an explicit target: the bear takes exactly 1 damage and survives. */
    private static boolean testCreatureDamage() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        Card eugen = makeCard("Eugen, Loyalist of Awakened Blue", p, game);
        addToBattlefield(eugen);
        Card bear = makeCard("Grizzly Bears", q, game);
        addToBattlefield(bear);

        SpellAbility trig = AbilityFactory.getAbility(eugen, "TrigBeginCombat");
        trig.setActivatingPlayer(p);
        trig.getTargets().add(bear);
        game.getStack().add(trig);
        playUntilStackClear(game);

        boolean ok = bear.getDamage() == 1 && !game.getZoneOf(bear).is(ZoneType.Graveyard);
        System.out.println("[CreatureDamage] bear damage = " + bear.getDamage() + " (expect 1, alive) -> "
                + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** No opponent creatures: the fallback deals 1 damage to the opponent player instead. */
    private static boolean testFallbackPlayerDamage() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        Card eugen = makeCard("Eugen, Loyalist of Awakened Blue", p, game);
        addToBattlefield(eugen);

        SpellAbility trig = AbilityFactory.getAbility(eugen, "TrigBeginCombat");
        trig.setActivatingPlayer(p);
        game.getStack().add(trig);
        playUntilStackClear(game);

        boolean ok = q.getLife() == 19;
        System.out.println("[FallbackPlayer] opponent life = " + q.getLife() + " (expect 19) -> "
                + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /**
     * End-to-end: the Phase trigger really fires at Begin Combat of BOTH
     * players' turns (Phase$ BeginCombat | ValidPlayer$ Player). With no
     * opponent creatures the fallback pings the opponent player each combat.
     */
    private static boolean testFiresOnBothPlayersCombat() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        Card eugen = makeCard("Eugen, Loyalist of Awakened Blue", p, game);
        game.getAction().changeZone(null, p.getZone(ZoneType.Battlefield), eugen, null, null);
        game.getTriggerHandler().registerActiveTrigger(eugen, false);

        // your combat phase start: fallback pings the opponent (no opponent creatures)
        fireAtBeginCombat(game, p);
        boolean yourTurn = q.getLife() == 19;

        // opponent's combat phase start: the same trigger fires again (each turn)
        fireAtBeginCombat(game, q);
        boolean oppTurn = q.getLife() == 18;

        boolean ok = yourTurn && oppTurn;
        System.out.println("[FiresEachTurn] after your begin combat q life = " + (yourTurn ? 19 : q.getLife())
                + ", after opponent's begin combat q life = " + (oppTurn ? 18 : q.getLife())
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /**
     * Mimics PhaseHandler.onPhaseBegin: rebuild active triggers for the new
     * phase, fire the Phase trigger (queued as simultaneous stack entries),
     * then unfreeze the stack and resolve it like the main loop does.
     */
    private static void fireAtBeginCombat(Game game, Player turnPlayer) {
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, turnPlayer);
        game.getTriggerHandler().resetActiveTriggers();
        game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(turnPlayer), false);
        game.getStack().unfreezeStack();
        playUntilStackClear(game);
    }
}
