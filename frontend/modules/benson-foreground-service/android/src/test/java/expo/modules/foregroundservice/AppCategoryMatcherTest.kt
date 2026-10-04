package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppCategoryMatcherTest {
  @Test fun cautaAplicatiaDe() = assertEquals("parcare", AppCategoryMatcher.extractCategory("cauta aplicatia de parcare"))
  @Test fun deschideAplicatiaDe() = assertEquals("taxi", AppCategoryMatcher.extractCategory("deschide aplicatia de taxi"))
  @Test fun diacriticsAndPunctuation() = assertEquals("vreme", AppCategoryMatcher.extractCategory("Caută aplicația de vreme."))
  @Test fun rejectsPlainOpen() = assertNull(AppCategoryMatcher.extractCategory("deschide whatsapp"))
  @Test fun rejectsPlainSearch() = assertNull(AppCategoryMatcher.extractCategory("cauta madonna pe youtube"))
}
