import java.util.Map;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardPlayOption;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;

/**
 * Headless behavioral test for Anila, Guardian of the South-Southwest (GBF).
 *
 * 1. ETB: target creature loses all abilities and becomes a 0/1 Sheep creature.
 *    That creature's controller may play one card they own from exile this turn
 *    (implemented via Animate staticAbilities$ -> the MayPlay static is hosted
 *    ON the target creature, so its controller is the one granted MayPlay).
 * 2. Leaving the battlefield exiles all Sheep and Goats.
 * 3. The Warp keyword (K:Warp) is present.
 *
 * Note on the dev environment: when the ETB trigger fires through the real
 * trigger path, the AI picks Anila itself as the target when it is the only
 * (or preferred) legal target; the transform/MayPlay tests therefore drive the
 * real scripted TrigETB chain with an explicitly chosen target (GbfArrietTest
 * pattern). The trigger path itself is covered by testSelfTargetETB.
 */
public class GbfAnilaTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= testSelfTargetETB();
        ok &= testETBTransformAndMayPlay();
        ok &= testLeaveExilesSheepGoat();
        ok &= testWarpKeywordPresent();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /**
     * Real trigger path: when Anila enters (with no other creatures around),
     * the ETB fires, the AI picks a legal target, and that target creature
     * becomes a 0/1 Sheep (here Anila itself).
     */
    private static boolean testSelfTargetETB() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card anila = makeCard("Anila, Guardian of the South-Southwest", p, game);
        enterBattlefield(game, anila);
        game.getTriggerHandler().runWaitingTriggers();
        playUntilStackClear(game);

        boolean ok = anila.getNetPower() == 0 && anila.getNetToughness() == 1
                && anila.getType().hasCreatureType("Sheep");
        System.out.println("[SelfETB] anila PT=" + anila.getNetPower() + "/" + anila.getNetToughness()
                + " types=" + anila.getType() + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /**
     * Drives the scripted TrigETB chain with an explicitly chosen target
     * (opponent's Serra Angel, Flying+Vigilance 4/4) and checks:
     * - the target becomes a 0/1 Sheep without abilities (permanent),
     * - the MayPlay static is hosted on the target creature (its controller),
     * - after a static refresh the opponent is granted MayPlay on their own
     *   exiled card.
     */
    private static boolean testETBTransformAndMayPlay() {
        Game game = newGame();
        Player p = game.getPlayers().get(1); // Anila's controller
        Player q = game.getPlayers().get(0); // opponent; also controls target creature

        Card anila = makeCard("Anila, Guardian of the South-Southwest", p, game);
        addToBattlefield(anila);

        Card target = makeCard("Serra Angel", q, game); // Flying, Vigilance, 4/4
        addToBattlefield(target);

        Card exiled = makeCard("Lightning Bolt", q, game); // a card q owns in exile
        game.getAction().changeZone(null, q.getZone(ZoneType.Exile), exiled, null, null);

        // Drive the real scripted chain with an explicit target
        SpellAbility etb = AbilityFactory.getAbility(anila, "TrigETB");
        etb.setActivatingPlayer(p);
        etb.getTargets().add(target);
        game.getStack().add(etb);
        playUntilStackClear(game);

        boolean pt = target.getNetPower() == 0 && target.getNetToughness() == 1;
        boolean isSheep = target.isCreature() && target.getType().hasCreatureType("Sheep");
        boolean lostAbilities = !target.hasKeyword(Keyword.FLYING) && !target.hasKeyword(Keyword.VIGILANCE);
        System.out.println("[ETB] target PT=" + target.getNetPower() + "/" + target.getNetToughness()
                + " types=" + target.getType() + " isSheep=" + isSheep
                + " lostAbilities=" + lostAbilities + " -> " + (pt && isSheep && lostAbilities ? "PASS" : "FAIL"));

        // The MayPlay static must be hosted ON the target creature
        boolean staticHosted = false;
        for (StaticAbility st : target.getStaticAbilities()) {
            if (st.hasParam("MayPlay")) {
                staticHosted = true;
                System.out.println("[ETB] found MayPlay static on target, host=" + st.getHostCard().getName());
            }
        }

        // After a static refresh, q (target creature's controller) must be
        // granted MayPlay on the exiled card q owns
        game.getAction().checkStaticAbilities();
        boolean mayPlayGranted = false;
        Map<StaticAbility, CardPlayOption> mp = exiled.getMayPlay();
        if (mp != null) {
            for (CardPlayOption opt : mp.values()) {
                if (opt.getPlayer() == q) {
                    mayPlayGranted = true;
                    System.out.println("[ETB] MayPlay granted to " + opt.getPlayer()
                            + " (target controller) for " + exiled.getName());
                }
            }
        }
        System.out.println("[ETB] staticHosted=" + staticHosted + " mayPlayGrantedToTargetController=" + mayPlayGranted
                + " -> " + (staticHosted && mayPlayGranted ? "PASS" : "FAIL"));

        return pt && isSheep && lostAbilities && staticHosted && mayPlayGranted;
    }

    /**
     * When Anila leaves the battlefield, all Sheep and Goats on the battlefield
     * are exiled (the transformed target + an unrelated Goat), while Anila
     * herself (Draph God) is not.
     */
    private static boolean testLeaveExilesSheepGoat() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        Card anila = makeCard("Anila, Guardian of the South-Southwest", p, game);
        // dev-mode changeZone: puts her on the battlefield without firing ETB
        game.getAction().changeZone(null, p.getZone(ZoneType.Battlefield), anila, null, null);
        game.getTriggerHandler().registerActiveTrigger(anila, false);

        Card target = makeCard("Serra Angel", q, game);
        addToBattlefield(target);

        Card goat = makeCard("Zodiac Goat", q, game); // an unrelated Goat on the battlefield
        addToBattlefield(goat);

        // Transform the target into a Sheep via the real chain
        SpellAbility etb = AbilityFactory.getAbility(anila, "TrigETB");
        etb.setActivatingPlayer(p);
        etb.getTargets().add(target);
        game.getStack().add(etb);
        playUntilStackClear(game);

        boolean targetIsSheep = target.getType().hasCreatureType("Sheep");
        boolean goatIsGoat = goat.getType().hasCreatureType("Goat");

        // Anila leaves the battlefield (to graveyard), then TrigLeave fires
        game.getAction().moveTo(p.getZone(ZoneType.Graveyard), anila, null);

        // Drive TrigLeave chain directly (ChangeZoneAll exiles all Sheep+Goats).
        // Note: the real trigger path for a self-leaving LTB trigger is not
        // queued by moveTo in this dev environment (Origin$ Battlefield is a
        // looks-back-in-time trigger); the chain below is the scripted Execute.
        SpellAbility leave = AbilityFactory.getAbility(anila, "TrigLeave");
        leave.setActivatingPlayer(p);
        game.getStack().add(leave);
        playUntilStackClear(game);

        boolean targetExiled = target.getZone().getZoneType() == ZoneType.Exile;
        boolean goatExiled = goat.getZone().getZoneType() == ZoneType.Exile;
        boolean anilaInGraveyard = anila.getZone().getZoneType() == ZoneType.Graveyard;
        System.out.println("[Leave] targetIsSheep=" + targetIsSheep + " goatIsGoat=" + goatIsGoat
                + " targetExiled=" + targetExiled + " goatExiled=" + goatExiled
                + " anilaInGraveyard=" + anilaInGraveyard
                + " -> " + (targetIsSheep && goatIsGoat && targetExiled && goatExiled && anilaInGraveyard ? "PASS" : "FAIL"));
        return targetIsSheep && goatIsGoat && targetExiled && goatExiled && anilaInGraveyard;
    }

    /** The Warp keyword must be present. */
    private static boolean testWarpKeywordPresent() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card anila = makeCard("Anila, Guardian of the South-Southwest", p, game);
        boolean hasWarp = false;
        for (KeywordInterface kw : anila.getKeywords()) {
            if (kw.getKeyword() == Keyword.WARP) {
                hasWarp = true;
                System.out.println("[Warp] keyword=" + kw.getKeyword() + " cost=" + kw.getReminderText()
                        + " -> " + (hasWarp ? "PASS" : "FAIL"));
            }
        }
        return hasWarp;
    }
}
