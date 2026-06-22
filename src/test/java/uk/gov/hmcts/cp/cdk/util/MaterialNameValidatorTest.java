package uk.gov.hmcts.cp.cdk.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaterialNameValidatorTest {

    @Test
    void shouldReturnNullWhenInputIsNull() {
        assertThat(MaterialNameValidator.truncateMaterialName(null))
                .isNull();
    }

    @Test
    void shouldReturnBlankStringWhenInputIsBlank() {
        assertThat(MaterialNameValidator.truncateMaterialName(""))
                .isEmpty();

        assertThat(MaterialNameValidator.truncateMaterialName("   "))
                .isEqualTo("   ");
    }

    @Test
    void shouldReturnOriginalNameWhenLengthIsWithinLimit() {
        final String fileName = "document.pdf";

        assertThat(MaterialNameValidator.truncateMaterialName(fileName))
                .isEqualTo(fileName);
    }

    @Test
    void shouldReturnOriginalNameWhenLengthIsExactlyMaxLength() {
        final String fileName = "a".repeat(46) + ".pdf"; // total length = 50

        assertThat(fileName).hasSize(50);

        assertThat(MaterialNameValidator.truncateMaterialName(fileName))
                .isEqualTo(fileName);
    }

    @Test
    void shouldTruncateFileNameWithoutExtension() {
        final String fileName = "a".repeat(60);

        final String result = MaterialNameValidator.truncateMaterialName(fileName);

        assertThat(result)
                .hasSize(50)
                .isEqualTo("a".repeat(50));
    }

    @Test
    void shouldTruncateBaseNameAndPreserveExtension() {
        final String fileName = "a".repeat(60) + ".pdf";

        final String result = MaterialNameValidator.truncateMaterialName(fileName);

        assertThat(result)
                .hasSize(50)
                .endsWith(".pdf")
                .isEqualTo("a".repeat(46) + ".pdf");
    }

    @Test
    void shouldPreserveLongerExtensionWhenTruncating() {
        final String fileName = "a".repeat(60) + ".tar.gz";

        final String result = MaterialNameValidator.truncateMaterialName(fileName);

        assertThat(result)
                .hasSize(50)
                .endsWith(".gz")
                .isEqualTo("a".repeat(47) + ".gz");
    }

    @Test
    void shouldUseLastDotAsExtensionSeparator() {
        final String fileName = "very.long.file.name." + "a".repeat(40) + ".pdf";

        final String result = MaterialNameValidator.truncateMaterialName(fileName);

        assertThat(result)
                .hasSizeLessThanOrEqualTo(50)
                .endsWith(".pdf");
    }

}