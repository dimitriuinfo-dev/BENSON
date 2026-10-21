package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiSearchVerificationTest {

  @Test fun searchFieldNotFoundIsFailedNotUnverified() {
    assertEquals(
      PoiSearchVerdict.SEARCH_FAILED,
      classifyPoiSearch(searchFieldFound = false, textEntered = false, screenChangedAfterSubmit = false, contentElementCount = 0),
    )
  }

  @Test fun textEntryFailedIsFailed() {
    assertEquals(
      PoiSearchVerdict.SEARCH_FAILED,
      classifyPoiSearch(searchFieldFound = true, textEntered = false, screenChangedAfterSubmit = false, contentElementCount = 0),
    )
  }

  @Test fun submittedButScreenNeverChangedIsUnverifiedNotFailed() {
    // CAZUL REAL incidentului: nu stim daca a fost o incompatibilitate sau doar o intarziere —
    // nu confundam cu un esec confirmat.
    assertEquals(
      PoiSearchVerdict.SEARCH_UNVERIFIED,
      classifyPoiSearch(searchFieldFound = true, textEntered = true, screenChangedAfterSubmit = false, contentElementCount = 0),
    )
  }

  @Test fun screenChangedWithZeroContentIsSuccessEmptyNotFailed() {
    // Regula explicita din mandat: zero rezultate NU e un esec tehnic.
    assertEquals(
      PoiSearchVerdict.SEARCH_SUCCESS_EMPTY,
      classifyPoiSearch(searchFieldFound = true, textEntered = true, screenChangedAfterSubmit = true, contentElementCount = 0),
    )
  }

  @Test fun screenChangedWithContentIsSuccessResults() {
    assertEquals(
      PoiSearchVerdict.SEARCH_SUCCESS_RESULTS,
      classifyPoiSearch(searchFieldFound = true, textEntered = true, screenChangedAfterSubmit = true, contentElementCount = 5),
    )
  }

  @Test fun onlyFailedAndUnverifiedJustifyTryingAnotherRoute() {
    assertTrue(poiSearchJustifiesAlternativeRoute(PoiSearchVerdict.SEARCH_FAILED))
    assertTrue(poiSearchJustifiesAlternativeRoute(PoiSearchVerdict.SEARCH_UNVERIFIED))
    assertFalse(poiSearchJustifiesAlternativeRoute(PoiSearchVerdict.SEARCH_SUCCESS_RESULTS))
    assertFalse(poiSearchJustifiesAlternativeRoute(PoiSearchVerdict.SEARCH_SUCCESS_EMPTY))
  }
}
