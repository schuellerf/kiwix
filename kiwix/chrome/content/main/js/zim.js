/*
 * Copyright 2011 Emmanuel Engelhart <kelson@kiwix.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU  General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */

var currentZimAccessor;

/* try a ZIM file */
function openZimFile(path) {
    /* Create the ZIM accessor */
    var zimAccessorService = Components.classes["@kiwix.org/zimAccessor"].getService();
    var zimAccessor = zimAccessorService.QueryInterface(Components.interfaces.IZimAccessor);

    /* Return if not able to open the file */
    var file =
	Components.classes["@mozilla.org/file/local;1"].
	createInstance(Components.interfaces.nsILocalFile);
    try {
	file.initWithPath(path);
    } catch(er) {
	return;
    }

    if (isFile(file.path) && zimAccessor.loadFile(file.path)) {
	currentZimAccessor = zimAccessor;
	return currentZimAccessor;
    }
}

/* Load the current ZIM file */
function openCurrentBook() {
    var currentBook = library.getCurrentBook();
    if (!currentBook) return false;
    return manageOpenFile(currentBook.path, true);
}

/* Return the homepage of a ZIM file */
function getCurrentZimFileHomePageUrl() {
    var homePageUrl;

    if (currentZimAccessor) {
	var url = new Object();

	/* Return the welcome path if exists */
	currentZimAccessor.getMainPageUrl(url);
	if (url.value != undefined && url.value != '') {
	    homePageUrl = "zim://" + url.value;	
	}
    }
    
    return homePageUrl;
}

/* Load a ramdom page */
function loadRandomArticle() {
    if (currentZimAccessor != undefined) {
	var url = new Object();
	
	currentZimAccessor.getRandomPageUrl(url);
	if (url.value != undefined && url.value != '')
	    url.value = "zim://" + url.value;	
	
	toggleLibrary(false);
	loadContent(url.value);
	activateBackButton();
    }
}

/* Load article from title */
function loadArticleFromTitle(title) {
    if (currentZimAccessor != undefined) {
	var url = new Object();
	
	currentZimAccessor.getPageUrlFromTitle(title, url);
	if (url.value != undefined && url.value != '') {
	    
	    /* Need to replace the '+' by the escaping value, otherwise will be interpreted as ' ' (see with "C++") */
	    var urlValue = url.value.replace( /\+/g, "%2B");

	    url.value = "zim://" + urlValue;
	    loadContent(url.value);
	    activateBackButton();
	    return true;
	}
    }
    
    return false;
}

/* Check the integrity (with a checksum) of the ZIM file */
function checkIntegrity() {
    if (currentZimAccessor != undefined) {
	if (canCheckIntegrity()) {
	    return !(currentZimAccessor.isCorrupted());
	} else {
	    dump("Unable to check the integrity of the current ZIMf file.\n");
	}
    }
}

/* Verify if the file has a checksum */
function canCheckIntegrity() {
    if (currentZimAccessor != undefined) {
	return currentZimAccessor.canCheckIntegrity();
    }
}

/* Check if a zim file is open */
function isBookOpen() {
    return (currentZimAccessor != undefined);
}