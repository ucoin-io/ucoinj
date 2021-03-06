package org.duniter.elasticsearch.user.dao;

/*
 * #%L
 * duniter4j-elasticsearch-plugin
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

import org.duniter.elasticsearch.user.dao.group.*;
import org.duniter.elasticsearch.user.dao.page.*;
import org.duniter.elasticsearch.user.dao.profile.*;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Module;

public class DaoModule extends AbstractModule implements Module {

    @Override protected void configure() {

        // User
        bind(UserIndexDao.class).to(UserIndexDaoImpl.class).asEagerSingleton();
        bind(UserProfileDao.class).to(UserProfileDaoImpl.class).asEagerSingleton();
        bind(UserSettingsDao.class).to(UserSettingsDaoImpl.class).asEagerSingleton();

        // Page
        bind(PageIndexDao.class).to(PageIndexDaoImpl.class).asEagerSingleton();
        bind(PageCommentDao.class).to(PageCommentDaoImpl.class).asEagerSingleton();
        bind(PageRecordDao.class).to(PageRecordDaoImpl.class).asEagerSingleton();

        // Group
        bind(GroupIndexDao.class).to(GroupIndexDaoImpl.class).asEagerSingleton();
        bind(GroupCommentDao.class).to(GroupCommentDaoImpl.class).asEagerSingleton();
        bind(GroupRecordDao.class).to(GroupRecordDaoImpl.class).asEagerSingleton();

    }

}