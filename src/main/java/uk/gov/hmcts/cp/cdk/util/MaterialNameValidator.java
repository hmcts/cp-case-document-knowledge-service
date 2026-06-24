package uk.gov.hmcts.cp.cdk.util;

import org.apache.commons.lang3.StringUtils;

public class MaterialNameValidator {

    private static final int MAX_LENGTH = 50;

    public static String truncateMaterialName(String fullFileName) {
        if (StringUtils.isBlank(fullFileName)) {
            return fullFileName;
        }

        if (fullFileName.length() <= MAX_LENGTH) {
            return fullFileName;
        }

        int dotIndex = StringUtils.lastIndexOf(fullFileName, '.');

        if (dotIndex == StringUtils.INDEX_NOT_FOUND) {
            // No extension — just truncate
            return StringUtils.truncate(fullFileName, MAX_LENGTH);
        }

        String extension = fullFileName.substring(dotIndex); // e.g. ".pdf"
        String baseName = fullFileName.substring(0, dotIndex);
        int allowedBaseLength = MAX_LENGTH - extension.length();

        return StringUtils.truncate(baseName, allowedBaseLength) + extension;
    }
}
