/*
 * Copyright (c) 1998-2012 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *   Free SoftwareFoundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package org.ireland.jnetty.dispatch.filter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.servlet.Filter;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.ireland.jnetty.webapp.WebApp;
import org.springframework.util.Assert;


/**
 * Manages the Filters.
 */
public class FilterManager
{
	static final Log log = LogFactory.getLog(FilterManager.class.getName());
	
	private final WebApp _webApp;

	private final ServletContext _servletContext;
	

	//<filterName,FilterConfigImpl>
	private LinkedHashMap<String, FilterConfigImpl> _filters = new LinkedHashMap<String, FilterConfigImpl>();

	
	
	//记录 filterName 到 urlPatterns 之间的映射关系 
	//maps filterName to urlPattern   <filterName,Set<urlPattern>>
	private Map<String, Set<String>> _urlPatterns = new HashMap<String, Set<String>>();

	
	//maps filterName to servletName   <filterName,Set<ServletName>>
	private Map<String, Set<String>> _servletNames = new HashMap<String, Set<String>>();

	
	public FilterManager(WebApp _webApp, ServletContext _servletContext)
	{
		super();
		this._webApp = _webApp;
		this._servletContext = _servletContext;
	}

	/**
	 * Adds a filter to the filter manager.
	 */
	public void addFilter(FilterConfigImpl config)
	{
		Assert.notNull(config);
		
		_filters.put(config.getFilterName(), config);
	}

	/**
	 * Adds a filter to the filter manager.
	 */
	public FilterConfigImpl getFilter(String filterName)
	{
		return _filters.get(filterName);
	}

	public HashMap<String, FilterConfigImpl> getFilters()
	{
		return _filters;
	}

	/**
	 * Initialize filters that need starting at server start.
	 */
	@PostConstruct
	public void init()
	{
		for (String name : _filters.keySet())
		{
			try
			{
				createFilter(name);
			} catch (Exception e)
			{
				log.warn(e.toString(), e);
			}
		}
	}

	public void addFilterMapping(FilterMapping filterMapping)
	{
		//添加FilterName --> urlPatterns 的映射关系
		Set<String> patterns = filterMapping.getURLPatterns();
		if (patterns != null && patterns.size() > 0)
		{
			Set<String> urls = _urlPatterns.get(filterMapping.getFilterConfig().getName());

			if (urls == null)
			{
				urls = new LinkedHashSet<String>();

				_urlPatterns.put(filterMapping.getFilterConfig().getName(), urls);
			}

			urls.addAll(patterns);
		}

		
		//添加FilterName --> servletNames 的映射关系
		List<String> servletNames = filterMapping.getServletNames();
		if (servletNames != null && servletNames.size() > 0)
		{
			Set<String> names = _servletNames.get(filterMapping.getFilterConfig().getName());

			if (names == null)
			{
				names = new HashSet<String>();

				_servletNames.put(filterMapping.getFilterConfig().getName(), names);
			}

			names.addAll(servletNames);
		}
	}

	public Set<String> getUrlPatternMappings(String filterName)
	{
		return _urlPatterns.get(filterName);
	}

	public Set<String> getServletNameMappings(String filterName)
	{
		return _servletNames.get(filterName);
	}

	/**
	 * Instantiates a filter given its configuration.
	 * 
	 * @param filterName
	 *            the filter
	 * 
	 * @return the initialized filter.
	 */
	private Filter createFilter(String filterName) throws ServletException
	{
		FilterConfigImpl config = _filters.get(filterName);

		if (config == null)
			throw new ServletException(filterName + " is not a known filter.  Filters must be defined by <filter> before being used.");

		synchronized (config)
		{
			try
			{
				Filter filter = config.getInstance();

				if (filter != null)
					return filter;
				else
					throw new ServletException("Create Filter Error");
				
			} catch (ServletException e)
			{
				// XXX: log(e.getMessage(), e);

				// XXX: config.setInitException(e);

				throw e;
			} catch (Throwable e)
			{
				// XXX: log(e.getMessage(), e);

				throw new ServletException(e);
			}
		}
	}

	public void destroy()
	{
		ArrayList<Filter> filterList = new ArrayList<Filter>();

		for (int i = 0; i < filterList.size(); i++)
		{
			Filter filter = filterList.get(i);

			try
			{

				filter.destroy();
			} catch (Throwable e)
			{
				log.warn(e.toString(), e);
			}
		}
	}
}
