package wl.ai.rag.RAG_Coding;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcdAlphaIndexTraversalServiceTest {

    @Test
    void amphetamineUseDisorderSevere_queryContainsUse_followsUseOf_F1590() {
        IcdAlphaIndexTraversalService s = new IcdAlphaIndexTraversalService();
        Optional<IcdAlphaIndexTraversalService.TraversalResult> r =
                s.tryResolveUseOfChain("Amphetamine use disorder, severe");
        assertTrue(r.isPresent(), "icdalpha_2026.json should be loadable and path resolvable");
        // "use" in query → Use (of) only → stimulant NEC default when no child subterm matches
        assertEquals("F15.90", r.get().code());
    }

    @Test
    void amphetamineUseDisorderMild_F1590() {
        IcdAlphaIndexTraversalService s = new IcdAlphaIndexTraversalService();
        Optional<IcdAlphaIndexTraversalService.TraversalResult> r =
                s.tryResolveUseOfChain("Amphetamine use disorder, mild");
        assertTrue(r.isPresent());
        assertEquals("F15.90", r.get().code());
    }

    @Test
    void methamphetamineDisorderSevere_noUseWord_amphetamineTypeShortcut_F1520() {
        IcdAlphaIndexTraversalService s = new IcdAlphaIndexTraversalService();
        Optional<IcdAlphaIndexTraversalService.TraversalResult> r =
                s.tryResolveUseOfChain("Methamphetamine disorder, severe");
        assertTrue(r.isPresent());
        assertEquals("F15.20", r.get().code());
    }

    @Test
    void inRemission_matchesSubterm() {
        IcdAlphaIndexTraversalService s = new IcdAlphaIndexTraversalService();
        Optional<IcdAlphaIndexTraversalService.TraversalResult> r =
                s.tryResolveUseOfChain("Amphetamine use in remission");
        assertTrue(r.isPresent());
        assertEquals("F15.91", r.get().code());
    }
}
