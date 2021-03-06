package org.fluxtream.connectors.fitbit;

import org.fluxtream.core.connectors.annotations.ObjectTypeSpec;
import org.fluxtream.core.domain.AbstractLocalTimeFacet;
import org.hibernate.annotations.Index;

import javax.persistence.Entity;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;

/**
 *
 * @author Candide Kemmler (candide@fluxtream.com)
 */
@Entity(name="Facet_FitbitWeight")
@ObjectTypeSpec(name = "weight", value = 8, prettyname = "Weight", isDateBased = true)
@NamedQueries({
      @NamedQuery(name = "fitbit.weight.byDate",
                  query = "SELECT facet FROM Facet_FitbitWeight facet WHERE facet.apiKeyId=? AND facet.date=?"),
      @NamedQuery(name = "fitbit.weight.latest",
                  query = "SELECT facet FROM Facet_FitbitWeight facet WHERE facet.apiKeyId=? ORDER BY facet.start DESC")
})
public class FitbitWeightFacet extends AbstractLocalTimeFacet {

    public double bmi;
    public double fat;
    public double weight;

    @Index(name="logId")
    public Long logId;

    public FitbitWeightFacet() {
        super();
    }

    public FitbitWeightFacet(final long apiKeyId) {
        super(apiKeyId);
    }

    @Override
    protected void makeFullTextIndexable() {}

}
