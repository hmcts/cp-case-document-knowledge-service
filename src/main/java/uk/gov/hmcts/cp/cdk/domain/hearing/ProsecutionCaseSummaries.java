package uk.gov.hmcts.cp.cdk.domain.hearing;
import java.util.List;

public class ProsecutionCaseSummaries {
    private List<Defendants> defendants;
    private String id;

    // getters & setters
    public List<Defendants> getDefendants() {
        return defendants;
    }
    public void setDefendants(List<Defendants> defendants) {
        this.defendants = defendants;
    }
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
}