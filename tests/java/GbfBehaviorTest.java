import java.util.List;

import com.google.common.collect.Lists;

import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Headless behavioral test for Anthuria, Dancer of the Sun (GBF).
 * 1) Death trigger (sacrifice -> draw -> tokens equal to +0/+1 counters).
 * 2) ETB counter SVar math (difference of mana values of remembered cards).
 */
public class GbfBehaviorTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testDeathTrigger();
        ok &= testEtbCounterMath();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    private static boolean testDeathTrigger() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);

        Card anthuria = makeCard("Anthuria, Dancer of the Sun", p, game);
        // dev-mode changeZone: puts her on the battlefield without firing her ETB.
        // Note: the dev path only registers extrinsic triggers, so register the full set explicitly.
        game.getAction().changeZone(null, p.getZone(ZoneType.Battlefield), anthuria, null, null);
        game.getTriggerHandler().registerActiveTrigger(anthuria, false);
        // put 3 x +0/+1 counters on her (as if the ETB had placed them)
        anthuria.addCounterInternal(CounterEnumType.P0P1, 3, p, true, null, AbilityKey.newMap());

        int handBefore = p.getZone(ZoneType.Hand).size();

        // another creature enters under p's control via a REAL zone change -> sacrifice trigger fires
        Card other = makeCard("Elvish Mystic", p, game);
        p.getZone(ZoneType.Hand).add(other);
        game.getAction().moveTo(p.getZone(ZoneType.Battlefield), other, null);
        // the zone change queued the ChangesZone trigger as "waiting" (holdTrigger);
        // flush it so the sacrifice trigger's ability goes onto the stack
        game.getTriggerHandler().runWaitingTriggers();

        playUntilStackClear(game);

        int p0p1 = anthuria.getCounters(CounterEnumType.P0P1);
        boolean inGrave = game.getZoneOf(anthuria) != null
                && game.getZoneOf(anthuria).getZoneType() == ZoneType.Graveyard;
        int handDelta = p.getZone(ZoneType.Hand).size() - handBefore;
        int tokens = 0;
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if ("Erune Token".equals(c.getName())) {
                tokens++;
            }
        }

        // handDelta expectation: entering creature left the hand (-1), the draw added (+1) -> net 0
        boolean ok = inGrave && p0p1 == 3 && handDelta == 0 && tokens == 3;
        System.out.println("[DeathTrigger] inGraveyard=" + inGrave + " p0p1=" + p0p1
                + " handDelta=" + handDelta + " (net 0 = -1 other +1 draw) eruneTokens=" + tokens
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    private static boolean testEtbCounterMath() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);

        Card anthuria = makeCard("Anthuria, Dancer of the Sun", p, game);
        addToBattlefield(anthuria);

        // simulate post-bounce state: your creature (CMC 1) in your hand,
        // opponent's creature (CMC 4) in opponent's hand, both remembered by Anthuria
        Card mine = makeCard("Elvish Mystic", p, game);   // CMC 1
        Card theirs = makeCard("Hill Giant", opp, game);  // CMC 4
        p.getZone(ZoneType.Hand).add(mine);
        opp.getZone(ZoneType.Hand).add(theirs);
        anthuria.addRemembered(mine);
        anthuria.addRemembered(theirs);

        SpellAbility sa = AbilityFactory.getAbility(
                "DB$ PutCounter | Defined$ Self | CounterType$ P0P1 | CounterNum$ X", anthuria);
        sa.setActivatingPlayer(p);

        int x = AbilityUtils.calculateAmount(anthuria, "X", sa);
        int maxMV = AbilityUtils.calculateAmount(anthuria, "MaxMV", sa);
        int minMV = AbilityUtils.calculateAmount(anthuria, "MinMV", sa);

        // demonstrate the OLD broken expression silently returns 0
        anthuria.setSVar("OldMaxMV", "Count$ValidHand Card.IsRemembered$GreatestCardManaValue");
        int oldMax = AbilityUtils.calculateAmount(anthuria, "OldMaxMV", sa);

        boolean ok = maxMV == 4 && minMV == 1 && x == 3;
        System.out.println("[ETBMath] MaxMV=" + maxMV + " MinMV=" + minMV + " X=" + x
                + " (old buggy expression would give " + oldMax + ") -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }
}
