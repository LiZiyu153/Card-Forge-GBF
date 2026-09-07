import java.util.List;

import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardDamageTable;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Regression tests for the Batch-3 real-game verification failures
 * (see verification-todo.md section E). Each test drives the fixed
 * mechanism headless and asserts the corrected behavior.
 */
public class GbfDiagTest extends GbfTestBase {

    public static void main(String[] args) {
        init();
        boolean ok = true;
        ok &= trilokOptionalCostRegistered();
        ok &= trilokCharmX1();
        ok &= trilokCharmX3();
        ok &= cosmosEndStepChain();
        ok &= athenaRedirect();
        ok &= protectSmileReplacement();
        ok &= galleonDeathReplacement();
        ok &= fedielDeathReplacement();
        ok &= yggdrasilAnimateLand();
        ok &= europaManaAbilityAllowed();
        ok &= wamdusGraveyardSpellChain();
        ok &= wamdusNoDrawWhenBouncedNotOwned();
        ok &= lichRegenerateCost();
        ok &= anneTokenUpkeepExile();
        ok &= fedielEtbExilesGraveyards();
        ok &= galleonR2OriginRestriction();
        ok &= reiAttachAndClone();
        ok &= scytheReflectDamage();
        ok &= aetheryteDebuffExile();
        ok &= selflessSalvationDraw();
        System.out.println("DONEALL");
        System.out.println(ok ? "ALL PASS" : "SOME FAILED");
        System.exit(ok ? 0 : 1);
    }

    /** Trilok: the OptionalCost static is registered for the spell. */
    private static boolean trilokOptionalCostRegistered() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card card = makeCard("Trilok Smash", p, game);
        addToHand(card);
        SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        List<OptionalCostValue> opts = GameActionUtil.getOptionalCostValues(sa);
        boolean ok = !opts.isEmpty();
        System.out.println("[TrilokOptCost] optional cost list size = " + opts.size()
                + " (expect >0) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Trilok: X=1 (not paid) -> one chosen mode resolves (mechanism check). */
    private static boolean trilokCharmX1() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card card = makeCard("Trilok Smash", p, game);
        addToHand(card);
        Card bird = makeCard("Storm Crow", q, game);
        addToBattlefield(bird);
        Card ench = makeCard("Holy Armor", q, game);
        addToBattlefield(ench);
        Card grave = makeCard("Grizzly Bears", q, game);
        game.getAction().moveToGraveyard(grave, null);
        game.getAction().checkStaticAbilities(false);

        SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        int legal = CharmEffect.makePossibleOptions(sa).size();
        List<AbilitySub> choices = CharmEffect.makePossibleOptions(sa);
        CharmEffect.chainAbilities(sa, new java.util.ArrayList<>(List.of(choices.get(0))));
        // set the chosen mode's target
        SpellAbility mode = sa.getSubAbility();
        if (mode.getParam("ValidTgts") != null) {
            String valid = mode.getParam("ValidTgts");
            if (valid.contains("withFlying")) {
                mode.getTargets().add(bird);
            } else if (valid.equals("Enchantment")) {
                mode.getTargets().add(ench);
            } else {
                mode.getTargets().add(grave);
            }
        }
        game.getStack().add(sa);
        playUntilStackClear(game);

        boolean effectHappened = !game.getZoneOf(bird).is(ZoneType.Battlefield)
                || !game.getZoneOf(ench).is(ZoneType.Battlefield)
                || !game.getZoneOf(grave).is(ZoneType.Graveyard);
        boolean ok = legal == 3 && effectHappened && game.getStack().isEmpty();
        System.out.println("[TrilokCharmX1] legal=" + legal + " (expect 3) mode resolved with effect="
                + effectHappened + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Trilok: X=3 (paid) -> all three modes resolve. */
    private static boolean trilokCharmX3() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card card = makeCard("Trilok Smash", p, game);
        addToHand(card);
        Card bird = makeCard("Storm Crow", q, game);
        addToBattlefield(bird);
        Card ench = makeCard("Holy Armor", q, game);
        addToBattlefield(ench);
        Card grave = makeCard("Grizzly Bears", q, game);
        game.getAction().moveToGraveyard(grave, null);
        game.getAction().checkStaticAbilities(false);

        SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        List<OptionalCostValue> opts = GameActionUtil.getOptionalCostValues(sa);
        SpellAbility paid = GameActionUtil.addOptionalCosts(sa, opts);
        int legal = CharmEffect.makePossibleOptions(paid).size();
        int x = AbilityUtils.calculateAmount(card, "X", paid);
        List<AbilitySub> choices = CharmEffect.makePossibleOptions(paid);
        CharmEffect.chainAbilities(paid, new java.util.ArrayList<>(choices));
        // set each mode's target
        for (SpellAbility s = paid.getSubAbility(); s != null; s = s.getSubAbility()) {
            String valid = s.getParam("ValidTgts");
            if (valid != null && valid.contains("withFlying")) {
                s.getTargets().add(bird);
            } else if (valid != null && valid.equals("Enchantment")) {
                s.getTargets().add(ench);
            } else {
                s.getTargets().add(grave);
            }
        }
        game.getStack().add(paid);
        playUntilStackClear(game);

        boolean allEffects = !game.getZoneOf(bird).is(ZoneType.Battlefield)
                && !game.getZoneOf(ench).is(ZoneType.Battlefield)
                && !game.getZoneOf(grave).is(ZoneType.Graveyard);
        boolean ok = x == 3 && legal == 3 && allEffects && game.getStack().isEmpty();
        System.out.println("[TrilokCharmX3] X=" + x + " (expect 3) legal=" + legal + " (expect 3)"
                + " all three effects=" + allEffects + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Cosmos: end-step chain removes all counters and deals that much damage to the chosen player. */
    private static boolean cosmosEndStepChain() {
        // drive the Minus chain directly (deterministic): remove 3 M1M1 -> 3 damage to chosen player + creatures
        Game game2 = newGame();
        Player p2 = game2.getPlayers().get(1);
        Player q2 = game2.getPlayers().get(0);
        Card cosmos2 = makeCard("Cosmos, Relived Beast of Arbitration", p2, game2);
        addToBattlefield(cosmos2);
        cosmos2.addCounterInternal(CounterEnumType.M1M1, 3, p2, true, new GameEntityCounterTable(),
                forge.game.ability.AbilityKey.newMap());
        Card bear2 = makeCard("Grizzly Bears", q2, game2);
        addToBattlefield(bear2);
        cosmos2.setChosenPlayer(q2);
        SpellAbility minus = AbilityFactory.getAbility(cosmos2, "Minus");
        minus.setActivatingPlayer(p2);
        game2.getStack().add(minus);
        playUntilStackClear(game2);
        boolean okDirect = cosmos2.getCounters(CounterEnumType.M1M1) == 0
                && q2.getLife() == 17 && bear2.getDamage() == 3;

        // full trigger chain: the AI picks one option; either path must be consistent
        Game game3 = newGame();
        Player p3 = game3.getPlayers().get(1);
        Player q3 = game3.getPlayers().get(0);
        Card cosmos3 = makeCard("Cosmos, Relived Beast of Arbitration", p3, game3);
        addToBattlefield(cosmos3);
        cosmos3.addCounterInternal(CounterEnumType.M1M1, 3, p3, true, new GameEntityCounterTable(),
                forge.game.ability.AbilityKey.newMap());
        Card bear3 = makeCard("Grizzly Bears", q3, game3);
        addToBattlefield(bear3);
        cosmos3.setChosenPlayer(q3);

        SpellAbility trig = AbilityFactory.getAbility(cosmos3, "TrigChoice");
        trig.setActivatingPlayer(p3);
        game3.getStack().add(trig);
        playUntilStackClear(game3);

        int m1 = cosmos3.getCounters(CounterEnumType.M1M1);
        int p1 = cosmos3.getCounters(CounterEnumType.P1P1);
        int totalLife = p3.getLife() + q3.getLife();
        // the AI picks one option: either M1M1 removed + 3 damage, or P1P1 (0) removed + 0 damage
        boolean okChain = (m1 == 0 && p1 == 0 && totalLife == 37 && bear3.getDamage() == 3)
                || (m1 == 3 && p1 == 0 && totalLife == 40 && bear3.getDamage() == 0);
        boolean ok = okDirect && okChain;
        System.out.println("[CosmosChain] directMinus=" + okDirect + " (expect M1M1=0 q=17 bear=3)"
                + " fullChain=" + okChain + " (M1M1=" + m1 + ") -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Athena: damage from the chosen source is redirected to the chosen creature. */
    private static boolean athenaRedirect() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card athena = makeCard("Athena, Goddess of Defense", p, game);
        addToBattlefield(athena);
        Card goblin = makeCard("Raging Goblin", p, game);
        addToBattlefield(goblin);
        Card bear = makeCard("Grizzly Bears", p, game);
        addToBattlefield(bear);

        // drive the effect creation: chosen source = goblin, redirect target = bear
        athena.setChosenCards(java.util.Collections.singletonList(goblin));
        SpellAbility eff = AbilityFactory.getAbility(athena, "DBEffect");
        eff.setActivatingPlayer(p);
        eff.getTargets().add(bear);
        game.getStack().add(eff);
        playUntilStackClear(game);

        int lifeBefore = p.getLife();
        CardDamageTable dmgMap = new CardDamageTable();
        CardDamageTable prevMap = new CardDamageTable();
        dmgMap.put(goblin, p, 2);
        game.getAction().dealDamage(false, dmgMap, prevMap, new GameEntityCounterTable(), null);
        runTriggersAndClear(game);

        int lifeAfter = p.getLife();
        boolean ok = lifeAfter == lifeBefore && bear.getDamage() == 2;
        System.out.println("[AthenaRedirect] p life " + lifeBefore + "->" + lifeAfter
                + " (expect unchanged), bear damage=" + bear.getDamage() + " (expect 2) -> "
                + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Protect This Smile Forever: damage -> mill, then put a permanent from milled onto the battlefield. */
    private static boolean protectSmileReplacement() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card protect = makeCard("Protect This Smile Forever", p, game);
        addToBattlefield(protect);
        Card goblin = makeCard("Raging Goblin", p, game);
        addToBattlefield(goblin);
        // give the library real cards so the mill has permanents to choose from
        for (int i = 0; i < 5; i++) {
            Card land = makeCard("Forest", p, game);
            p.getZone(ZoneType.Library).add(land);
        }
        try {
            CardDamageTable dmgMap = new CardDamageTable();
            CardDamageTable prevMap = new CardDamageTable();
            dmgMap.put(goblin, p, 2);
            game.getAction().dealDamage(false, dmgMap, prevMap, new GameEntityCounterTable(), null);
            runTriggersAndClear(game);
            int life = p.getLife();
            int lib = p.getZone(ZoneType.Library).size();
            int bf = p.getZone(ZoneType.Battlefield).size();
            System.out.println("[ProtectSmile] life=" + life + " (expect 20) lib=" + lib
                    + " battlefield=" + bf);
            boolean ok = life == 20 && lib == 3 && bf == 3;
            System.out.println("[ProtectSmile] -> " + (ok ? "PASS" : "FAIL"));
            return ok;
        } catch (Exception e) {
            System.out.println("[ProtectSmile] EXCEPTION: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return false;
        }
    }

    /** Galleon: another nontoken creature dying -> exiled instead (no crash). */
    private static boolean galleonDeathReplacement() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card galleon = makeCard("Galleon, Gold of the Six Dragons", p, game);
        addToBattlefield(galleon);
        Card bear = makeCard("Grizzly Bears", p, game);
        addToBattlefield(bear);
        try {
            game.getAction().destroy(bear, null, false, null);
            runTriggersAndClear(game);
            ZoneType zone = game.getZoneOf(bear) == null ? null : game.getZoneOf(bear).getZoneType();
            boolean ok = zone == ZoneType.Exile;
            System.out.println("[GalleonDeath] bear in " + zone + " (expect Exile) -> "
                    + (ok ? "PASS" : "FAIL"));
            return ok;
        } catch (Exception e) {
            System.out.println("[GalleonDeath] EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /** Fediel: creature about to die is exiled instead (no crash). */
    private static boolean fedielDeathReplacement() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card fediel = makeCard("Fediel, Black of the Six Dragons", p, game);
        addToBattlefield(fediel);
        Card bear = makeCard("Grizzly Bears", p, game);
        addToBattlefield(bear);
        try {
            game.getAction().destroy(bear, null, false, null);
            runTriggersAndClear(game);
            ZoneType zone = game.getZoneOf(bear) == null ? null : game.getZoneOf(bear).getZoneType();
            boolean ok = zone == ZoneType.Exile;
            System.out.println("[FedielDeath] bear in " + zone + " (expect Exile) -> "
                    + (ok ? "PASS" : "FAIL"));
            return ok;
        } catch (Exception e) {
            System.out.println("[FedielDeath] EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /** Yggdrasil: target land becomes X/X Primal Elf creature AND is still a land. */
    private static boolean yggdrasilAnimateLand() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card ygg = makeCard("Yggdrasil, Sprouting of the Great Tree", p, game);
        addToBattlefield(ygg);
        Card land = makeCard("Forest", p, game);
        addToBattlefield(land);

        SpellAbility sa = null;
        for (SpellAbility a : ygg.getSpellAbilities()) {
            if (a.getParam("SpellDescription") != null && a.getParam("SpellDescription").contains("becomes an X/X")) {
                sa = a;
                break;
            }
        }
        sa.setActivatingPlayer(p);
        sa.getTargets().add(land);
        game.getStack().add(sa);
        playUntilStackClear(game);

        boolean isLand = land.isLand();
        boolean isCreature = land.isCreature();
        boolean ok = isLand && isCreature;
        System.out.println("[Yggdrasil] land still land=" + isLand + " creature=" + isCreature
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Europa: snow permanents' activated abilities can't be activated, but MANA abilities still can. */
    private static boolean europaManaAbilityAllowed() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card europa = makeCard("Europa, Fair Maiden of Mercury", p, game);
        addToBattlefield(europa);
        Card forest = makeCard("Forest", p, game);
        addToBattlefield(forest);
        game.getAction().checkStaticAbilities(false);

        SpellAbility manaSA = null;
        for (SpellAbility a : forest.getSpellAbilities()) {
            if (a.getApi() != null && a.getApi().name().equals("Mana")) {
                manaSA = a;
                break;
            }
        }
        boolean ok = manaSA != null && manaSA.canPlay();
        System.out.println("[Europa] forest mana ability canPlay="
                + (manaSA != null && manaSA.canPlay()) + " (expect true) -> "
                + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Wamdus: instant cast from graveyard -> after resolving, bottom of library + bounce + draw. */
    private static boolean wamdusGraveyardSpellChain() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card wamdus = makeCard("Wamdus, Azure of the Six Dragons", p, game);
        addToBattlefield(wamdus);
        game.getTriggerHandler().registerActiveTrigger(wamdus, false);

        Card creature = makeCard("Grizzly Bears", p, game);
        addToBattlefield(creature);

        Card instant = makeCard("Giant Growth", p, game);
        p.getZone(ZoneType.Graveyard).add(instant);
        // put the spell on the stack as if it were cast from the graveyard
        Card stackCard = game.getAction().moveToStack(instant, instant.getFirstSpellAbility());
        stackCard.setCastFrom(p.getZone(ZoneType.Graveyard));
        stackCard.setCastSA(stackCard.getFirstSpellAbility());

        // drive the SpellCast trigger's Execute (DB$ Effect with the replacement)
        SpellAbility trig = AbilityFactory.getAbility(wamdus, "TrigEffect");
        trig.setActivatingPlayer(p);
        trig.setTriggeringObject(forge.game.ability.AbilityKey.Card, stackCard);
        game.getStack().add(trig);
        playUntilStackClear(game);

        // the spell resolves: move it from the stack to the graveyard
        game.getAction().changeZone(game.getZoneOf(stackCard), p.getZone(ZoneType.Graveyard), stackCard, null, null);
        runTriggersAndClear(game);

        ZoneType instZone = game.getZoneOf(stackCard) == null ? null : game.getZoneOf(stackCard).getZoneType();
        ZoneType creatureZone = game.getZoneOf(creature) == null ? null : game.getZoneOf(creature).getZoneType();
        ZoneType wamdusZone = game.getZoneOf(wamdus) == null ? null : game.getZoneOf(wamdus).getZoneType();

        boolean onBottom = instZone == ZoneType.Library;
        // the AI target pick may bounce Wamdus itself; either is a legal "target creature"
        boolean bounced = creatureZone == ZoneType.Hand || wamdusZone == ZoneType.Hand;
        boolean drew = p.getZone(ZoneType.Hand).size() >= 1;
        boolean ok = onBottom && bounced && drew;
        System.out.println("[WamdusChain] spell zone=" + instZone + " (expect Library) creature="
                + creatureZone + " wamdus=" + wamdusZone + " drew=" + drew + " -> "
                + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Lich: regenerate cost = sacrifice a Zombie + remove a counter from Lich. */
    private static boolean lichRegenerateCost() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card lich = makeCard("Lich, Gloomy, Wicked, Tricky Girl", p, game);
        addToBattlefield(lich);
        lich.addCounterInternal(CounterEnumType.P1P1, 2, p, true, new GameEntityCounterTable(),
                forge.game.ability.AbilityKey.newMap());
        Card zombie = makeCard("Graveborn Muse", p, game);
        // use a real Zombie instead
        zombie = makeCard("Diregraf Ghoul", p, game);
        addToBattlefield(zombie);

        SpellAbility regen = null;
        for (SpellAbility a : lich.getSpellAbilities()) {
            if (a.getApi() != null && a.getApi().name().equals("Regenerate")) {
                regen = a;
                break;
            }
        }
        regen.setActivatingPlayer(p);
        boolean withResources = regen.getPayCosts().canPay(regen, p, false);

        // without a Zombie
        Game game2 = newGame();
        Player p2 = game2.getPlayers().get(1);
        Card lich2 = makeCard("Lich, Gloomy, Wicked, Tricky Girl", p2, game2);
        addToBattlefield(lich2);
        lich2.addCounterInternal(CounterEnumType.P1P1, 1, p2, true, new GameEntityCounterTable(),
                forge.game.ability.AbilityKey.newMap());
        SpellAbility regen2 = null;
        for (SpellAbility a : lich2.getSpellAbilities()) {
            if (a.getApi() != null && a.getApi().name().equals("Regenerate")) {
                regen2 = a;
                break;
            }
        }
        regen2.setActivatingPlayer(p2);
        boolean withoutZombie = regen2.getPayCosts().canPay(regen2, p2, false);

        // without counters on Lich
        Card lich3 = makeCard("Lich, Gloomy, Wicked, Tricky Girl", p2, game2);
        addToBattlefield(lich3);
        Card zombie3 = makeCard("Diregraf Ghoul", p2, game2);
        addToBattlefield(zombie3);
        SpellAbility regen3 = null;
        for (SpellAbility a : lich3.getSpellAbilities()) {
            if (a.getApi() != null && a.getApi().name().equals("Regenerate")) {
                regen3 = a;
                break;
            }
        }
        regen3.setActivatingPlayer(p2);
        boolean withoutCounters = regen3.getPayCosts().canPay(regen3, p2, false);

        boolean ok = regen != null && withResources && !withoutZombie && !withoutCounters;
        System.out.println("[LichCost] withResources=" + withResources + " (expect true)"
                + " withoutZombie=" + withoutZombie + " (expect false)"
                + " withoutCounters=" + withoutCounters + " (expect false) -> "
                + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Anne: attack trigger makes the token; the token exiles itself at its controller's upkeep. */
    private static boolean anneTokenUpkeepExile() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card anne = makeCard("Anne, Princess Mysteria", p, game);
        addToBattlefield(anne);
        // 3 Lesson cards in graveyard
        for (int i = 0; i < 3; i++) {
            Card lesson = makeCard("Introduction to Annihilation", p, game);
            p.getZone(ZoneType.Graveyard).add(lesson);
        }

        SpellAbility trig = AbilityFactory.getAbility(anne, "TrigToken");
        trig.setActivatingPlayer(p);
        game.getStack().add(trig);
        playUntilStackClear(game);

        Card token = null;
        for (Card c : p.getZone(ZoneType.Battlefield)) {
            if (c.getName().equals("Elemental Token")) {
                token = c;
                break;
            }
        }
        boolean created = token != null;
        if (token != null) {
            game.getTriggerHandler().registerActiveTrigger(token, false);
        }

        // fire the upkeep phase trigger
        if (token != null) {
            game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, p);
            game.getTriggerHandler().resetActiveTriggers();
            game.getTriggerHandler().runTrigger(TriggerType.Phase, forge.game.ability.AbilityKey.mapFromPlayer(p), false);
            game.getStack().unfreezeStack();
            playUntilStackClear(game);
        }
        boolean exiled = token != null && game.getZoneOf(token) != null && game.getZoneOf(token).is(ZoneType.Exile);
        boolean ok = created && exiled;
        System.out.println("[AnneToken] created=" + created + " exiledAtUpkeep=" + exiled
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Wamdus: draw only when the bounced creature's owner is you (ConditionDefined$ ChosenCard regression). */
    private static boolean wamdusNoDrawWhenBouncedNotOwned() {
        // scenario 1: chosen creature owned by the opponent -> no draw
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card wamdus = makeCard("Wamdus, Azure of the Six Dragons", p, game);
        addToBattlefield(wamdus);
        Card qBear = makeCard("Grizzly Bears", q, game);
        addToBattlefield(qBear);
        wamdus.setChosenCards(java.util.Collections.singletonList(qBear));
        SpellAbility draw = AbilityFactory.getAbility(wamdus, "TrigDraw");
        draw.setActivatingPlayer(p);
        game.getStack().add(draw);
        playUntilStackClear(game);
        int handAfterOpp = p.getZone(ZoneType.Hand).size();

        // scenario 2: chosen creature owned by you -> draw (library must have a card to draw)
        Game game2 = newGame();
        Player p2 = game2.getPlayers().get(1);
        Card wamdus2 = makeCard("Wamdus, Azure of the Six Dragons", p2, game2);
        addToBattlefield(wamdus2);
        Card pBear = makeCard("Grizzly Bears", p2, game2);
        addToBattlefield(pBear);
        Card libCard = makeCard("Forest", p2, game2);
        p2.getZone(ZoneType.Library).add(libCard);
        wamdus2.setChosenCards(java.util.Collections.singletonList(pBear));
        SpellAbility draw2 = AbilityFactory.getAbility(wamdus2, "TrigDraw");
        draw2.setActivatingPlayer(p2);
        game2.getStack().add(draw2);
        playUntilStackClear(game2);
        int handAfterSelf = p2.getZone(ZoneType.Hand).size();

        boolean ok = handAfterOpp == 0 && handAfterSelf == 1;
        System.out.println("[WamdusDrawCond] opp-owned creature -> hand=" + handAfterOpp
                + " (expect 0) own creature -> hand=" + handAfterSelf
                + " (expect 1) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Fediel ETB: each player exiles all creature cards from their graveyard (ChangeType$ Creature). */
    private static boolean fedielEtbExilesGraveyards() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card fediel = makeCard("Fediel, Black of the Six Dragons", p, game);
        addToBattlefield(fediel);
        Card qBear = makeCard("Grizzly Bears", q, game);
        q.getZone(ZoneType.Graveyard).add(qBear);
        Card pBear = makeCard("Grizzly Bears", p, game);
        p.getZone(ZoneType.Graveyard).add(pBear);

        SpellAbility trig = AbilityFactory.getAbility(fediel, "TrigExileAll");
        trig.setActivatingPlayer(p);
        game.getStack().add(trig);
        playUntilStackClear(game);

        ZoneType qZone = game.getZoneOf(qBear) == null ? null : game.getZoneOf(qBear).getZoneType();
        ZoneType pZone = game.getZoneOf(pBear) == null ? null : game.getZoneOf(pBear).getZoneType();
        boolean ok = qZone == ZoneType.Exile && pZone == ZoneType.Exile;
        System.out.println("[FedielETB] opponent grave creature in " + qZone + " own in " + pZone
                + " (expect Exile/Exile) -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Galleon R2: from hand -> exile + counter on owner-controlled creature; from battlefield -> normal death. */
    private static boolean galleonR2OriginRestriction() {
        // from hand to graveyard: R2 applies -> exiled, +1/+1 on a creature the owner controls
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card galleon = makeCard("Galleon, Gold of the Six Dragons", p, game);
        addToBattlefield(galleon);
        Card bear = makeCard("Grizzly Bears", p, game);
        addToBattlefield(bear);
        Card ring = makeCard("Sol Ring", p, game);
        p.getZone(ZoneType.Hand).add(ring);
        game.getAction().moveToGraveyard(ring, null);
        runTriggersAndClear(game);
        boolean fromHand = game.getZoneOf(ring) != null && game.getZoneOf(ring).is(ZoneType.Exile);
        // Galleon itself is also a legal "creature its owner controls" choice; the dev AI may pick either
        boolean counter = bear.getCounters(CounterEnumType.P1P1) + galleon.getCounters(CounterEnumType.P1P1) == 1;

        // from battlefield: non-creature permanent destroyed -> normal graveyard (R2 must NOT apply)
        Game game2 = newGame();
        Player p2 = game2.getPlayers().get(1);
        Card galleon2 = makeCard("Galleon, Gold of the Six Dragons", p2, game2);
        addToBattlefield(galleon2);
        Card ring2 = makeCard("Sol Ring", p2, game2);
        addToBattlefield(ring2);
        game2.getAction().destroy(ring2, null, false, null);
        runTriggersAndClear(game2);
        boolean fromBattlefield = game2.getZoneOf(ring2) != null
                && game2.getZoneOf(ring2).is(ZoneType.Graveyard);

        boolean ok = fromHand && counter && fromBattlefield;
        System.out.println("[GalleonR2] hand->exile=" + fromHand + " counter=" + counter
                + " battlefield->graveyard=" + fromBattlefield + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Rei: standing = plain Enchantment Creature; {T} turns it into a plain Aura (non-creature, bestow-like form),
     *  attaches it to the creature and makes it a 1/1 copy WITH abilities; when the host dies, unattached Rei
     *  follows Aura rules (704.5m) into the graveyard and its form is restored for recasting from the graveyard. */
    private static boolean reiAttachAndClone() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card rei = makeCard("Rei, Master of the Heart-Discerning Eyes", p, game);
        addToBattlefield(rei);
        // standing (before {T}): plain Enchantment Creature, NOT an Aura -> 704.5m does not apply
        boolean standing = rei.isCreature() && !rei.isAura();
        // a creature card in the graveyard to copy (with an ability: flying)
        Card graveCrow = makeCard("Storm Crow", p, game);
        p.getZone(ZoneType.Graveyard).add(graveCrow);
        // a creature card in hand to put onto the battlefield
        Card handBear = makeCard("Grizzly Bears", p, game);
        p.getZone(ZoneType.Hand).add(handBear);

        SpellAbility ab = null;
        for (SpellAbility a : rei.getSpellAbilities()) {
            if (a.getApi() != null && a.getApi().name().equals("ChangeZone")) {
                ab = a;
                break;
            }
        }
        ab.setActivatingPlayer(p);
        game.getStack().add(ab);
        playUntilStackClear(game);

        Card enchanted = null;
        for (Card c : p.getZone(ZoneType.Battlefield)) {
            if (c.getName().equals("Storm Crow")) {
                enchanted = c;
                break;
            }
        }
        boolean attached = rei.getAttachedTo() != null && rei.getAttachedTo().equals(enchanted);
        // while attached Rei is a plain Aura (non-creature)
        boolean auraForm = rei.isAura() && !rei.isCreature();
        boolean handLeft = !game.getZoneOf(handBear).is(ZoneType.Hand);
        boolean copyOk = enchanted != null && enchanted.hasKeyword("Flying")
                && enchanted.getNetPower() == 1 && enchanted.getNetToughness() == 1;

        // host dies -> unattached Aura follows 704.5m into the graveyard; form restored on leaving play
        game.getAction().destroy(enchanted, null, false, null);
        game.getAction().checkStateEffects(false);
        boolean hostDead = game.getZoneOf(enchanted) != null && game.getZoneOf(enchanted).is(ZoneType.Graveyard);
        boolean reiDead = game.getZoneOf(rei) != null && game.getZoneOf(rei).is(ZoneType.Graveyard);
        boolean formRestored = !rei.isAura() && rei.isCreature();

        boolean ok = standing && copyOk && attached && auraForm && handLeft && hostDead && reiDead && formRestored;
        System.out.println("[ReiClone] standing=" + standing
                + " copy=" + (enchanted == null ? "null" : enchanted.getName())
                + " PT=" + (enchanted == null ? "-"
                        : enchanted.getNetPower() + "/" + enchanted.getNetToughness())
                + " flying=" + (enchanted != null && enchanted.hasKeyword("Flying"))
                + " attached=" + attached + " auraForm=" + auraForm + " handLeft=" + handLeft
                + " hostDead=" + hostDead + " reiDead=" + reiDead + " formRestored=" + formRestored
                + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Wicked Ebony Scythe (R42-2): when it is dealt damage, it deals twice that much damage
     *  to the source of that damage (DamageDone trigger must resolve the real source card). */
    private static boolean scytheReflectDamage() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card scythe = makeCard("Wicked Ebony Scythe", p, game);
        addToBattlefield(scythe);
        game.getTriggerHandler().registerActiveTrigger(scythe, false);
        Card bear = makeCard("Grizzly Bears", q, game);
        addToBattlefield(bear);
        try {
            CardDamageTable dmgMap = new CardDamageTable();
            CardDamageTable prevMap = new CardDamageTable();
            dmgMap.put(bear, scythe, 2);
            game.getAction().dealDamage(false, dmgMap, prevMap, new GameEntityCounterTable(), null);
            runTriggersAndClear(game);
            ZoneType bearZone = game.getZoneOf(bear) == null ? null : game.getZoneOf(bear).getZoneType();
            boolean ok = bear.getDamage() == 4 || bearZone == ZoneType.Graveyard;
            System.out.println("[ScytheReflect] bear damage=" + bear.getDamage() + " zone=" + bearZone
                    + " (expect 4 damage / dead) -> " + (ok ? "PASS" : "FAIL"));
            return ok;
        } catch (Exception e) {
            System.out.println("[ScytheReflect] EXCEPTION: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return false;
        }
    }

    /** Aetheryte Requiescat (R42-2): creatures target opponent controls get -1/-1 until end of turn;
     *  if a creature that got -1/-1 this way would die this turn, exile it instead. */
    private static boolean aetheryteDebuffExile() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Player q = game.getPlayers().get(0);
        Card aetheryte = makeCard("Aetheryte Requiescat", p, game);
        addToHand(aetheryte);
        Card bear = makeCard("Grizzly Bears", q, game);
        addToBattlefield(bear);
        try {
            SpellAbility sa = aetheryte.getFirstSpellAbility();
            sa.setActivatingPlayer(p);
            sa.getTargets().add(q);
            game.getStack().add(sa);
            playUntilStackClear(game);
            boolean debuffed = bear.getNetToughness() == 1; // 2/2 -> 1/1
            game.getAction().destroy(bear, null, false, null);
            runTriggersAndClear(game);
            ZoneType bearZone = game.getZoneOf(bear) == null ? null : game.getZoneOf(bear).getZoneType();
            boolean exiled = bearZone == ZoneType.Exile;
            boolean ok = debuffed && exiled;
            System.out.println("[Aetheryte] debuffed=" + debuffed + " (toughness "
                    + bear.getNetToughness() + ") destroyed bear zone=" + bearZone
                    + " (expect Exile) -> " + (ok ? "PASS" : "FAIL"));
            return ok;
        } catch (Exception e) {
            System.out.println("[Aetheryte] EXCEPTION: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return false;
        }
    }

    /** Selfless Salvation (R42-3): end step draw when a player gained 3+ life this turn.
     *  Diagnoses which XCount spelling actually computes: ConditionGE3 vs Highest. */
    private static boolean selflessSalvationDraw() {
        Game game = newGame();
        Player p = game.getPlayers().get(1);
        Card selfless = makeCard("Selfless Salvation", p, game);
        addToBattlefield(selfless);
        game.getTriggerHandler().registerActiveTrigger(selfless, false);

        // the player gains 3 life this turn (upkeep triggers are not driven here)
        p.gainLife(3, selfless, null);
        // feed the library so the draw has a card
        p.getZone(ZoneType.Library).add(makeCard("Forest", p, game));

        // diagnose the X SVar value the trigger checks (ConditionGE3 spelling, current script)
        SpellAbility trig = null;
        for (forge.game.trigger.Trigger t : selfless.getTriggers()) {
            String desc = t.getParam("TriggerDescription");
            if (desc != null && desc.contains("draw a card")) {
                trig = t.getOverridingAbility();
                break;
            }
        }
        int xCond = trig == null ? -1 : AbilityUtils.calculateAmount(selfless, "X", trig);
        // candidate fix: Highest aggregate spelling (KotEL style)
        int xHigh = trig == null ? -1
                : AbilityUtils.calculateAmount(selfless, "PlayerCountPlayers$HighestLifeGainedThisTurn", trig);
        int xHighReg = trig == null ? -1
                : AbilityUtils.calculateAmount(selfless, "PlayerCountRegistered$HighestLifeGainedThisTurn", trig);

        // fire the real End of Turn phase trigger (Eugen phase recipe)
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, p);
        game.getTriggerHandler().resetActiveTriggers();
        game.getTriggerHandler().runTrigger(TriggerType.Phase, forge.game.ability.AbilityKey.mapFromPlayer(p), false);
        game.getStack().unfreezeStack();
        playUntilStackClear(game);

        int hand = p.getZone(ZoneType.Hand).size();
        boolean ok = xHigh >= 3 && hand >= 1;
        System.out.println("[SelflessSalv] X(ConditionGE3)=" + xCond + " X(HighestPlayers)=" + xHigh
                + " X(HighestRegistered)=" + xHighReg + " hand=" + hand
                + " (expect X(Highest)>=3, hand>=1) -> "
                + (ok ? "PASS" : "FAIL"));
        return ok;
    }
}
