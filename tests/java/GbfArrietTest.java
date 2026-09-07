import java.util.List;

import com.google.common.collect.Lists;

import forge.game.ability.AbilityFactory;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Headless behavioral test for Arriet, Soothing Minstrel (GBF) mode 2:
 * "Put a +1/+0 counter on one target creature and a +0/+1 counter on
 * another target creature you control."
 *
 * Regression: the old script used a single PutCounter with
 * CounterType$ P1P0,P0P1 + DividedAsYouChoose -> the engine asked for ONE
 * counter type and put the SAME type on BOTH creatures.
 *
 * The fix chains two PutCounter abilities; the second target is restricted
 * with !IsRemembered so it must be a DIFFERENT creature ("another target").
 */
public class GbfArrietTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testSplitMode();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /**
     * Drives the mode-2 sub-ability chain directly (bypassing the Charm) and
     * checks that one creature got a +1/+0 counter and a DIFFERENT creature
     * got a +0/+1 counter, and that no creature got both (the old bug shape).
     */
    private static boolean testSplitMode() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);

        Card arriet = makeCard("Arriet, Soothing Minstrel", p, game);
        game.getAction().changeZone(null, p.getZone(ZoneType.Battlefield), arriet, null, null);
        game.getTriggerHandler().registerActiveTrigger(arriet, false);

        Card c1 = makeCard("Elvish Mystic", p, game);
        Card c2 = makeCard("Grizzly Bears", p, game);
        addToBattlefield(c1);
        addToBattlefield(c2);

        // Use the REAL scripted chain from the card file (SVar DBPutSplit).
        SpellAbility split = AbilityFactory.getAbility(arriet, "DBPutSplit");
        split.setActivatingPlayer(p);

        if (!split.setupTargets()) {
            System.out.println("[SplitMode] setupTargets failed -> FAIL");
            return false;
        }
        System.out.println("[SplitMode] first target chosen: " + split.getTargets().getTargetCards());
        System.out.println("[SplitMode] subAbility present: " + (split.getSubAbility() != null));
        game.getStack().add(split);
        playUntilStackClear(game);

        System.out.println("[SplitMode] after clear: remembered=" + arriet.getRemembered()
                + " stackEmpty=" + game.getStack().isEmpty());

        int p1p0c1 = c1.getCounters(CounterEnumType.P1P0);
        int p0p1c1 = c1.getCounters(CounterEnumType.P0P1);
        int p1p0c2 = c2.getCounters(CounterEnumType.P1P0);
        int p0p1c2 = c2.getCounters(CounterEnumType.P0P1);

        boolean exactlyOneP1P0 = p1p0c1 + p1p0c2 == 1;
        boolean exactlyOneP0P1 = p0p1c1 + p0p1c2 == 1;
        boolean noSameCreatureBoth = !(p1p0c1 > 0 && p0p1c1 > 0) && !(p1p0c2 > 0 && p0p1c2 > 0);
        boolean distinctCreatures = (p1p0c1 == 1 && p0p1c2 == 1) || (p0p1c1 == 1 && p1p0c2 == 1);
        boolean rememberedCleared = arriet.getRememberedCount() == 0;

        boolean ok = exactlyOneP1P0 && exactlyOneP0P1 && noSameCreatureBoth && distinctCreatures;
        System.out.println("[SplitMode] c1(P1P0=" + p1p0c1 + ",P0P1=" + p0p1c1 + ") c2(P1P0=" + p1p0c2
                + ",P0P1=" + p0p1c2 + ") exactlyOneP1P0=" + exactlyOneP1P0 + " exactlyOneP0P1=" + exactlyOneP0P1
                + " noSameCreatureBoth=" + noSameCreatureBoth + " distinctCreatures=" + distinctCreatures
                + " rememberedCleared=" + rememberedCleared + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }
}
