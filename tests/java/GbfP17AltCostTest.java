import java.util.List;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityAlternativeCost;
import forge.game.zone.ZoneType;

/**
 * Regression test for the Luminox Genesi alternative cost (engine patch P-17).
 *
 * The card lets you "pay life equal to a creature's mana value rather than pay
 * the mana cost of its activated abilities". Two engine bugs were fixed:
 *   1. ConvertedManaCost resolved to the PERMANENT's mana value, so every
 *      activated ability of a 2-drop charged a flat 2 life.
 *   2. The whole payCosts was replaced, silently dropping non-mana components
 *      (the {T} tap symbol), so the creature did not tap.
 *
 * Uses Violet Smoke Lost in the Night: a Creature (so the narrowed
 * ValidCard$ Creature.YouCtrl applies) with "{5}{U}, {T}: ..." -- it has both a
 * mana cost and a tap symbol, so both fixes are observable.
 */
public class GbfP17AltCostTest extends GbfTestBase {

    public static void main(String[] args) {
        init();
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        Card luminox = makeCard("Luminox Genesi", p, game);
        p.getZone(ZoneType.Battlefield).add(luminox);
        game.getAction().checkStaticAbilities(false);

        Card mine = makeCard("Violet Smoke Lost in the Night", p, game);
        p.getZone(ZoneType.Battlefield).add(mine);
        Card theirs = makeCard("Violet Smoke Lost in the Night", q, game);
        q.getZone(ZoneType.Battlefield).add(theirs);
        game.getAction().checkStaticAbilities(false);

        boolean ok = true;

        // --- own creature: alt cost must keep {T} and charge the ABILITY's cmc (6)
        SpellAbility ability = null;
        for (SpellAbility sa : mine.getSpellAbilities()) {
            if (!sa.isManaAbility() && sa.getPayCosts() != null
                    && sa.getPayCosts().toString().contains("{5}{U}")) {
                ability = sa;
            }
        }
        if (ability == null) {
            System.out.println("[P17] could not find Violet's activated ability -> FAIL");
            return;
        }
        List<SpellAbility> alts = StaticAbilityAlternativeCost.alternativeCosts(ability, mine, p);
        String alt = alts.isEmpty() ? "<none>" : alts.get(0).getPayCosts().toString();
        boolean keepsTap = alt.contains("{T}");
        boolean paysSix = alt.contains("6 life");
        boolean noFlatTwo = !alt.contains("2 life");
        System.out.println("[P17-own] base=" + ability.getPayCosts()
                + " alt=" + alt
                + " keepsTap=" + keepsTap + " pays6Life=" + paysSix
                + " -> " + ((keepsTap && paysSix && noFlatTwo) ? "PASS" : "FAIL"));
        ok &= keepsTap && paysSix && noFlatTwo;

        // --- opponent's creature: must NOT get the alternative cost
        SpellAbility oppAbility = null;
        for (SpellAbility sa : theirs.getSpellAbilities()) {
            if (!sa.isManaAbility() && sa.getPayCosts() != null
                    && sa.getPayCosts().toString().contains("{5}{U}")) {
                oppAbility = sa;
            }
        }
        List<SpellAbility> oppAlts = oppAbility == null
                ? java.util.Collections.emptyList()
                : StaticAbilityAlternativeCost.alternativeCosts(oppAbility, theirs, p);
        boolean excluded = oppAlts.isEmpty();
        System.out.println("[P17-opponent] altCosts=" + oppAlts.size()
                + " (expect 0) -> " + (excluded ? "PASS" : "FAIL"));
        ok &= excluded;

        // --- an artifact you control must NOT benefit either
        Card tome = makeCard("Jalum Tome", p, game);
        p.getZone(ZoneType.Battlefield).add(tome);
        game.getAction().checkStaticAbilities(false);
        int artifactAlts = 0;
        for (SpellAbility sa : tome.getSpellAbilities()) {
            if (!sa.isManaAbility()) {
                artifactAlts += StaticAbilityAlternativeCost.alternativeCosts(sa, tome, p).size();
            }
        }
        System.out.println("[P17-artifact] altCosts=" + artifactAlts
                + " (expect 0) -> " + (artifactAlts == 0 ? "PASS" : "FAIL"));
        ok &= artifactAlts == 0;

        System.out.println(ok ? "ALL PASS" : "FAILURES PRESENT");
        System.exit(ok ? 0 : 1);
    }
}
