/*
 * Copyright (C) 2012-2013 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * This file is part of Birthday Adapter.
 * 
 * Birthday Adapter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Birthday Adapter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Birthday Adapter.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.birthdayadapter.util;

import android.accounts.Account;

public class Constants {
    public static final String TAG = "Birthday Calendar";

    public static final String FULL_PACKAGE_NAME = "org.birthdayadapter";

    public static final String ACCOUNT_NAME = "Birthday Adapter";
    public static final String ACCOUNT_TYPE = "org.birthdayadapter.auth";
    public static final String CONTENT_AUTHORITY = "com.android.calendar";

    public static final Account ACCOUNT = new Account(ACCOUNT_NAME, ACCOUNT_TYPE);

    public static final String PREFS_NAME = "preferences";

    public static final int DISABLED_REMINDER = -99999;
}
