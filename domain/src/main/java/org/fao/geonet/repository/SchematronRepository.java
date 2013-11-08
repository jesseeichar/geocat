package org.fao.geonet.repository;

import java.util.List;

import org.fao.geonet.domain.Schematron;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Data Access object for the {@link Schematron} entities.
 * 
 * @author delawen
 */
public interface SchematronRepository extends
		GeonetRepository<Schematron, Integer>,
		JpaSpecificationExecutor<Schematron>, SchematronRepositoryCustom {
    /**
     * Look up a schematrons by its schema
     *
     * @param schema
     *            the name of the schema
     */
    public List<Schematron> findAllByIsoschema(String schema);
    /**
     * Look up a schematrons by its file
     *
     * @param file
     *            uri to the file
     */
    public List<Schematron> findAllByFile(String file);

}