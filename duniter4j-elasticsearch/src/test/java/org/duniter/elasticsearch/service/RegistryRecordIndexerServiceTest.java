package org.duniter.elasticsearch.service;

/*
 * #%L
 * UCoin Java Client :: ElasticSearch Indexer
 * %%
 * Copyright (C) 2014 - 2016 EIS
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import org.duniter.elasticsearch.TestResource;
import org.duniter.elasticsearch.service.registry.RecordRegistryService;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Created by Benoit on 06/05/2015.
 */
public class RegistryRecordIndexerServiceTest {
    private static final Logger log = LoggerFactory.getLogger(RegistryRecordIndexerServiceTest.class);

    @ClassRule
    public static final TestResource resource = TestResource.create();

    private RecordRegistryService service;

    @Before
    public void setUp() throws Exception {
        //service = ServiceLocator.instance().getRegistryRecordIndexerService();
    }

    @Test
    public void insertTestData() {
        service.insertRecordFromBulkFile(new File("src/test/resources/registry-test-records.json"));
    }

}
