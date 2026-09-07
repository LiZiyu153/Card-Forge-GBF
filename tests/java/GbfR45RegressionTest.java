import java.util.Collections;

import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardDamageTable;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Headless regression tests for the R45 batch fixes (2026-09-07 debug.txt +
 * Wings Shall Deliver You chapter III crash):
 *
 * 1. Alexiel — +1/+1 counter once per damage-prevention event, not per
 *    prevented point (CounterNum$ 1 instead of PreventedDamage).
 * 2. Nemone — enters with a charge counter (dead ETBWithCounters$ static
 *    replaced by the official K:etbCounter keyword).
 * 3. Soul Forge — enchanted creature is an artifact creature with
 *    indestructible AND deathtouch (repeated AddKeyword$ keys overwrite each
 *    other in parseToMap; official " & "-joined single param).
 * 4. Unconditional Friend — "first creature ... each turn" now really only
 *    fires for the first ETB of the turn on every turn (invalid FirstTime$ /
 *    PlayerTurn$ trigger params removed; CheckSVar count==1 gate).
 * 5. Before Dawn — the non-instant cards exiled by the dig are returned to
 *    the library (DigUntil RememberRevealed$ True + ChangeZoneAll of every
 *    remembered card still in exile).
 * 6. Wings Shall Deliver You chapter III — resolving the chapter no longer
 *    crashes ("CantBeCountered" is not a StaticAbilityMode; replacement
 *    Event$ Counter + Layer$ CantHappen instead).
 * 7. Ilsa — end step really fires when a creature you controlled died this
 *    turn (Count$ThisTurnDied_* is not an engine function;
 *    Count$ThisTurnEntered_Graveyard_from_Battlefield_Creature.YouCtrl).
 * 8. Heaven-Piercing Cannonlancer — same died-this-turn count fix for its
 *    ETB destroy.
 * 9. Where... are my rat ears? — the {3} sacrifice works for ANY player
 *    (DB$ SacrificeAll | Defined$ Self, soul_ransom official pattern;
 *    DB$ Sacrifice ignores Defined$).
 * 10. War Mecha — {X} with X>1 resolves (Repeat of single-pick
 *     GenericChoice instead of one multi-pick GenericChoice dialog that
 *     froze the human UI).
 * 11. Challenger With Footwork — target loses flying (DB$ Animate
 *     RemoveKeywords$; Pump ignores RemoveKeywords$).
 * 12. Story of Youthful Times adventure "What Might Have Been" — grants
 *     flashback (KW$ Flashback, snapcaster_mage official pattern).
 */
public class GbfR45RegressionTest extends GbfTestBase {

    public static void main(String[] args) {
        init();

        boolean ok = true;
        ok &= alexielOneCounterPerPreventionEvent();
        ok &= nemoneEtbChargeCounter();
        ok &= soulForgeGrantsArtifactIndestructibleDeathtouch();
        ok &= uncondFriendFirstEtbPerTurn();
        ok &= beforeDawnReturnsExiledToLibrary();
        ok &= wingsChapterIIINoCrash();
        ok &= wingsIIICounterHasNoEffect();
        ok &= ilsaEndStepAfterDeath();
        ok &= cannonlancerEtbDestroyAfterDeath();
        ok &= ratEarsSacrificeByAnyPlayer();
        ok &= warMechaX2Resolves();
        ok &= challengerRemovesFlying();
        ok &= storyAdventureGrantsFlashback();
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /** 5 damage to a bear you control -> 3 prevented (half rounded up), exactly ONE +1/+1 counter. */
    private static boolean alexielOneCounterPerPreventionEvent() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card alexiel = makeCard("Alexiel, Guardian of the Realm", p, game);
        addToBattlefield(alexiel);
        Card bear = makeCard("Grizzly Bears", p, game);
        addToBattlefield(bear);

        CardDamageTable dmgMap = new CardDamageTable();
        CardDamageTable prevMap = new CardDamageTable();
        dmgMap.put(alexiel, bear, 5);
        game.getAction().dealDamage(false, dmgMap, prevMap, new GameEntityCounterTable(), null);
        runTriggersAndClear(game);

        int counters = alexiel.getCounters(CounterEnumType.P1P1);
        int bearDmg = bear.getDamage();
        boolean ok = counters == 1 && bearDmg == 2;
        System.out.println("[AlexielPrevent] counters=" + counters + " (expect 1) bearDamage=" + bearDmg
                + " (expect 2 after 3 prevented) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Nemone enters with a charge counter (K:etbCounter:CHARGE:1). */
    private static boolean nemoneEtbChargeCounter() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card nemone = makeCard("Nemone, Like Drifting Clouds", p, game);
        enterBattlefield(game, nemone);
        int counters = nemone.getCounters(CounterEnumType.CHARGE);
        boolean ok = counters == 1;
        System.out.println("[NemoneEtb] charge counters=" + counters + " (expect 1) -> "
                + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Soul Forge: enchanted creature is an artifact creature with indestructible and deathtouch. */
    private static boolean soulForgeGrantsArtifactIndestructibleDeathtouch() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card forge = makeCard("Soul Forge", p, game);
        addToBattlefield(forge);
        Card bear = makeCard("Grizzly Bears", p, game);
        addToBattlefield(bear);
        forge.attachToEntity(bear, null);
        game.getAction().checkStaticAbilities();
        boolean ind = bear.hasKeyword("Indestructible");
        boolean dt = bear.hasKeyword("Deathtouch");
        boolean art = bear.isArtifact();
        boolean ok = ind && dt && art;
        System.out.println("[SoulForge] indestructible=" + ind + " deathtouch=" + dt + " isArtifact=" + art
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** First creature ETB of the turn triggers only once; second doesn't; opponent's first also triggers. */
    private static boolean uncondFriendFirstEtbPerTurn() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card uncond = makeCard("Unconditional Friend", p, game);
        enterBattlefield(game, uncond);

        Card bear1 = makeCard("Grizzly Bears", p, game);
        enterBattlefield(game, bear1);
        runTriggersAndClear(game);
        boolean firstGained = p.getLife() == 22;

        Card bear2 = makeCard("Grizzly Bears", p, game);
        enterBattlefield(game, bear2);
        runTriggersAndClear(game);
        boolean secondNotGained = p.getLife() == 22;

        Card qbear = makeCard("Grizzly Bears", q, game);
        enterBattlefield(game, qbear);
        runTriggersAndClear(game);
        boolean oppLost = q.getLife() == 18;

        boolean ok = firstGained && secondNotGained && oppLost;
        System.out.println("[UncondFirst] p life=" + p.getLife() + " q life=" + q.getLife()
                + " (expect 22/18; first ETB only) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Non-instant cards exiled by the dig return to the library; the found instant IS castable first. */
    private static boolean beforeDawnReturnsExiledToLibrary() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        // library top -> bottom: bear, forest, Giant Growth (instant, legal target: p's own bear)
        Card bear = makeCard("Grizzly Bears", p, game);
        p.getZone(ZoneType.Library).add(bear);
        Card forest = makeCard("Forest", p, game);
        p.getZone(ZoneType.Library).add(forest);
        Card growth = makeCard("Giant Growth", p, game);
        p.getZone(ZoneType.Library).add(growth);
        Card pBear = makeCard("Grizzly Bears", p, game);
        addToBattlefield(pBear);

        Card bd = makeCard("Before Dawn", p, game);
        SpellAbility sa = bd.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        game.getAction().moveToStack(bd, sa);
        game.getStack().add(sa);
        playUntilStackClear(game);

        int libSize = p.getZone(ZoneType.Library).size();
        int exSize = p.getZone(ZoneType.Exile).size();
        // name-level assertions: dev zone.add leaves stale card pointers, containers are authoritative
        java.util.Set<String> libNames = new java.util.HashSet<>();
        for (Card c : p.getZone(ZoneType.Library).getCards()) { libNames.add(c.getName()); }
        java.util.Set<String> gyNames = new java.util.HashSet<>();
        for (Card c : p.getZone(ZoneType.Graveyard).getCards()) { gyNames.add(c.getName()); }
        boolean bearBack = libNames.contains("Grizzly Bears");
        boolean forestBack = libNames.contains("Forest");
        // found instant: either cast (graveyard) or declined and returned to library bottom
        boolean growthOk = gyNames.contains("Giant Growth") || libNames.contains("Giant Growth");
        boolean ok = exSize == 0 && bearBack && forestBack && growthOk;
        System.out.println("[BeforeDawn] exile=" + exSize + " (expect 0) bearBack=" + bearBack
                + " forestBack=" + forestBack + " foundIn="
                + (gyNames.contains("Giant Growth") ? "Graveyard(cast)" : (libNames.contains("Giant Growth") ? "Library(returned)" : "???"))
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Wings III: countering a noncreature spell is attempted but has no effect (rule-correct). */
    private static boolean wingsIIICounterHasNoEffect() {
        boolean w = wingsCounterAttempt(true);
        boolean noW = !wingsCounterAttempt(false);
        boolean ok = w && noW;
        System.out.println("[WingsIIICounter] with effect spell survives=" + w
                + " without effect spell countered=" + noW + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Returns true when Opt resolves (not countered): library shrank by the draw. */
    private static boolean wingsCounterAttempt(boolean withWings) {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);

        if (withWings) {
            Card wings = makeCard("Wings Shall Deliver You", p, game);
            addToBattlefield(wings);
            SpellAbility dbiii = AbilityFactory.getAbility(wings, "DBIII");
            dbiii.setActivatingPlayer(p);
            game.getStack().add(dbiii);
            playUntilStackClear(game);
        }

        Card f1 = makeCard("Forest", p, game);
        p.getZone(ZoneType.Library).add(f1);
        Card f2 = makeCard("Forest", p, game);
        p.getZone(ZoneType.Library).add(f2);

        Card opt = makeCard("Opt", p, game);
        SpellAbility optSa = opt.getFirstSpellAbility();
        optSa.setActivatingPlayer(p);
        Card optStack = game.getAction().moveToStack(opt, optSa);
        optStack.setCastSA(optStack.getFirstSpellAbility());
        game.getStack().add(optSa);
        Card counter = makeCard("Counterspell", q, game);
        SpellAbility csa = counter.getFirstSpellAbility();
        csa.setActivatingPlayer(q);
        game.getAction().moveToStack(counter, csa);
        // counter targets are stored as SpellAbility objects (TargetChoices.getTargetSpells)
        csa.getTargets().add(optSa);
        game.getStack().add(csa);
        playUntilStackClear(game);

        int lib = p.getZone(ZoneType.Library).size();
        boolean resolved = lib == 1; // Opt drew one card -> 1 left; countered -> 2 left
        System.out.println("    [wings=" + withWings + "] p library=" + lib + " (1=Opt resolved, 2=was countered)");
        return resolved;
    }

    /** Resolving chapter III must not crash and must create the uncounterable effect. */
    private static boolean wingsChapterIIINoCrash() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card wings = makeCard("Wings Shall Deliver You", p, game);
        addToBattlefield(wings);
        try {
            SpellAbility dbiii = AbilityFactory.getAbility(wings, "DBIII");
            dbiii.setActivatingPlayer(p);
            game.getStack().add(dbiii);
            playUntilStackClear(game);
            int cmd = p.getZone(ZoneType.Command).size();
            boolean ok = cmd >= 1;
            System.out.println("[WingsIII] resolved without crash; command effects=" + cmd + " -> "
                    + (ok ? "PASS" : "FAIL"));
            return ok;
        } catch (Exception e) {
            System.out.println("[WingsIII] EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " -> FAIL");
            return false;
        }
    }

    /** Real end step: a creature you controlled died this turn -> lose 1 life and draw a card. */
    private static boolean ilsaEndStepAfterDeath() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card bearP = makeCard("Grizzly Bears", p, game);
        addToBattlefield(bearP);
        game.getAction().destroy(bearP, null, false, null);
        runTriggersAndClear(game);

        Card ilsa = makeCard("Ilsa, Ruthless Drill Sergeant", p, game);
        game.getAction().changeZone(null, p.getZone(ZoneType.Battlefield), ilsa, null, null);
        game.getTriggerHandler().registerActiveTrigger(ilsa, false);
        // draw needs a real library card (empty library draws nothing in dev)
        Card forest = makeCard("Forest", p, game);
        p.getZone(ZoneType.Library).add(forest);

        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, p);
        game.getTriggerHandler().resetActiveTriggers();
        game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(p), false);
        game.getStack().unfreezeStack();
        playUntilStackClear(game);

        boolean ok = p.getLife() == 19 && p.getZone(ZoneType.Hand).size() == 1;
        System.out.println("[IlsaEndStep] p life=" + p.getLife() + " (expect 19) hand="
                + p.getZone(ZoneType.Hand).size() + " (expect 1) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** ETB destroy fires when a creature you controlled died this turn (destroys SOME nonartifact permanent). */
    private static boolean cannonlancerEtbDestroyAfterDeath() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card bearP = makeCard("Grizzly Bears", p, game);
        addToBattlefield(bearP);
        game.getAction().destroy(bearP, null, false, null);
        runTriggersAndClear(game);

        Card qbear = makeCard("Grizzly Bears", q, game);
        addToBattlefield(qbear);
        Card cannon = makeCard("Heaven-Piercing Cannonlancer", p, game);
        enterBattlefield(game, cannon);
        runTriggersAndClear(game);

        ZoneType zq = game.getZoneOf(qbear) == null ? null : game.getZoneOf(qbear).getZoneType();
        ZoneType zc = game.getZoneOf(cannon) == null ? null : game.getZoneOf(cannon).getZoneType();
        boolean ok = zq == ZoneType.Graveyard || zc == ZoneType.Graveyard;
        System.out.println("[CannonEtb] qbear in " + zq + ", cannon in " + zc
                + " (one should be destroyed) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Any player (here: the OPPONENT who does not control the aura) may pay {3} and sacrifice it. */
    private static boolean ratEarsSacrificeByAnyPlayer() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card qbear = makeCard("Grizzly Bears", q, game);
        addToBattlefield(qbear);
        Card aura = makeCard("Where... are my rat ears?", p, game);
        addToBattlefield(aura);
        aura.attachToEntity(qbear, null);

        SpellAbility sac = findCost3(aura);
        if (sac == null) {
            System.out.println("[RatEars] {3} activated ability not found -> FAIL");
            return false;
        }
        sac.setActivatingPlayer(q);
        game.getStack().add(sac);
        playUntilStackClear(game);

        ZoneType z = game.getZoneOf(aura) == null ? null : game.getZoneOf(aura).getZoneType();
        boolean ok = z == ZoneType.Graveyard;
        System.out.println("[RatEars] aura in " + z + " after opponent activation (expect Graveyard) -> "
                + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    private static SpellAbility findCost3(Card card) {
        for (SpellAbility a : card.getSpellAbilities()) {
            if ("3".equals(a.getParam("Cost"))) {
                return a;
            }
        }
        return null;
    }

    /** {X} with X=2: two single-pick rounds resolve; the mecha gains at least one of the five abilities. */
    private static boolean warMechaX2Resolves() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card war = makeCard("War Mecha", p, game);
        addToBattlefield(war);

        SpellAbility sa = null;
        for (SpellAbility a : war.getSpellAbilities()) {
            if ("X".equals(a.getParam("Cost"))) {
                sa = a;
            }
        }
        if (sa == null) {
            System.out.println("[WarMecha] {X} ability not found -> FAIL");
            return false;
        }
        sa.setActivatingPlayer(p);
        sa.setXManaCostPaid(2);
        game.getStack().add(sa);
        try {
            playUntilStackClear(game);
        } catch (Exception e) {
            System.out.println("[WarMecha] EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " -> FAIL");
            return false;
        }
        String[] kws = {"Double Strike", "Vigilance", "Trample", "Lifelink", "Menace"};
        int gained = 0;
        for (String kw : kws) {
            if (war.hasKeyword(kw)) {
                gained++;
            }
        }
        boolean ok = gained >= 1 && game.getStack().isEmpty();
        System.out.println("[WarMecha] gained " + gained + " of the five keywords (X=2; >=1 expected), "
                + "stack cleared -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Challenger taps -> target creature you don't control loses flying until end of turn. */
    private static boolean challengerRemovesFlying() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card challenger = makeCard("Challenger With Footwork", p, game);
        enterBattlefield(game, challenger);
        Card angel = makeCard("Serra Angel", q, game);
        addToBattlefield(angel);
        if (!angel.hasKeyword("Flying")) {
            System.out.println("[Challenger] setup problem: Serra Angel has no flying -> FAIL");
            return false;
        }
        try {
            challenger.tap(false, null, p);
        } catch (Exception e) {
            System.out.println("[Challenger] tap EXCEPTION: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + " -> FAIL");
            return false;
        }
        runTriggersAndClear(game);
        boolean ok = !angel.hasKeyword("Flying");
        System.out.println("[Challenger] angel flying=" + angel.hasKeyword("Flying")
                + " (expect false after trigger) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Adventure "What Might Have Been": target instant in your graveyard gains flashback until end of turn. */
    private static boolean storyAdventureGrantsFlashback() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card story = makeCard("Story of Youthful Times", p, game);

        SpellAbility adv = null;
        for (SpellAbility a : story.getSpellAbilities()) {
            String sd = a.getParam("SpellDescription");
            if (sd != null && sd.contains("gains flashback")) {
                adv = a;
            }
        }
        if (adv == null) {
            System.out.println("[StoryAdv] adventure spell ability not found -> FAIL");
            return false;
        }
        Card bolt = makeCard("Lightning Bolt", p, game);
        game.getAction().changeZone(null, p.getZone(ZoneType.Graveyard), bolt, null, null);
        adv.setActivatingPlayer(p);
        adv.getTargets().add(bolt);
        game.getAction().moveToStack(story, adv);
        game.getStack().add(adv);
        try {
            playUntilStackClear(game);
        } catch (Exception e) {
            System.out.println("[StoryAdv] EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " -> FAIL");
            return false;
        }
        game.getAction().checkStaticAbilities();
        boolean ok = bolt.hasKeyword("Flashback");
        System.out.println("[StoryAdv] bolt hasFlashback=" + bolt.hasKeyword("Flashback")
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }
}
