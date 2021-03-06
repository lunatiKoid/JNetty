package org.ireland.jnetty.dispatch.servlet;
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
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */



import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.FilterChain;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.ireland.jnetty.config.ConfigException;
import org.ireland.jnetty.dispatch.ServletInvocation;
import org.ireland.jnetty.dispatch.filterchain.ErrorFilterChain;
import org.ireland.jnetty.dispatch.servlet.ServletConfigImpl;
import org.ireland.jnetty.dispatch.servlet.ServletManager;
import org.ireland.jnetty.dispatch.servlet.ServletMapping;
import org.ireland.jnetty.util.http.UrlMap;
import org.ireland.jnetty.webapp.WebApp;
import org.springframework.util.Assert;

import com.caucho.util.L10N;

/**
 * Manages dispatching: servlets and filters. :TODO: rename "ServletMapper" TO "ServletMatcher"
 * 
 * Servlet匹配器
 * 
 */
public class ServletMapperForTest
{
	private static final Logger LOG = Logger.getLogger(ServletMapperForTest.class.getName());

	private static final L10N L = new L10N(ServletMapperForTest.class);

	private final WebApp _webApp;

	private final ServletContext _servletContext;

	private final ServletManager _servletManager;

	// <urlPattern, ServletMapping>
	@Deprecated
	private UrlMap<ServletMapping> _servletMappings = new UrlMap<ServletMapping>();

	// 记录 urlPattern 到 <servlet-mapping>的映射关系(用于URL精确匹配)
	// 1:ServletMappings for Exact Match <urlPattern,ServletMapping>
	private Map<String, ServletMapping> _exactServletMappings = new HashMap<String, ServletMapping>();

	// 2:ServletMappings for Longest Prefix Match <prefixPattern,ServletMapping>(按pattern长度倒序排列)
	private SortedMap<String, ServletMapping> _prefixServletMappings = new TreeMap<String, ServletMapping>(new PatternLengthComparator());

	// 3:ServletMappings for Extension Match <Extension,ServletMapping> (按web.xml里出现的顺序排列)
	private LinkedHashMap<String, ServletMapping> _extensionServletMappings = new LinkedHashMap<String, ServletMapping>();

	// 4:Default servlet (urlPattern为"/",当无法找到匹配的Servlet或jsp时,则默认匹配的Servlet)
	private ServletConfigImpl _defaultServlet;

	// 记录 ServletName 到 urlPattern 之间的映射关系
	// Servlet 3.0 maps serletName to urlPattern <serletName,Set<urlPattern>>
	private Map<String, Set<String>> _urlPatterns = new HashMap<String, Set<String>>();

	public ServletMapperForTest(WebApp webApp, ServletContext servletContext, ServletManager servletManager)
	{
/*		Assert.notNull(webApp);
		Assert.notNull(servletContext);
		Assert.notNull(servletManager);*/

		_webApp = webApp;
		_servletContext = servletContext;
		_servletManager = servletManager;
	}

	// Getter and Setter---------------------------------------------------
	/**
	 * Gets the servlet context.
	 */
	public WebApp getWebApp()
	{
		return _webApp;
	}

	/**
	 * Returns the servlet manager.
	 */
	public ServletManager getServletManager()
	{
		return _servletManager;
	}

	// Getter and Setter---------------------------------------------------

	/**
	 * Adds a servlet mapping Specification: Servlet-3_1-PFD chapter 12.1
	 * 
	 * 增加 urlPattern + " -> " + ServletMapping 的映射关系
	 * 
	 */
	public void addUrlMapping(final String urlPattern, ServletMapping mapping) throws ServletException
	{
		try
		{

			String servletName = mapping.getServletConfig().getServletName();

/*			if (_servletManager.getServlet(servletName) == null)
				throw new ConfigException(
						L.l("'{0}' is an unknown servlet-name.  servlet-mapping requires that the named servlet be defined in a <servlet> configuration before the <servlet-mapping>.",
								servletName));*/

			if ("/".equals(urlPattern)) // Default servlet
			{
				_defaultServlet = mapping.getServletConfig();
			}


			// 添加到精确匹配的分类
			_exactServletMappings.put(urlPattern, mapping); 

			
			// 添加到前缀匹配的分类
			if (urlPattern.endsWith("/*")) 
			{
				_prefixServletMappings.put(urlPattern, mapping);
			}

			// 添加到扩展名匹配的分类
			if (urlPattern.startsWith("*.")) 
			{
				_extensionServletMappings.put(urlPattern, mapping);
			}

			//
			Set<String> patterns = _urlPatterns.get(servletName);

			if (patterns == null)
			{
				patterns = new HashSet<String>();

				_urlPatterns.put(servletName, patterns);
			}
			patterns.add(urlPattern);
			
			//
			LOG.config("servlet-mapping " + urlPattern + " -> " + servletName);
		}
		catch (RuntimeException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw ConfigException.create(e);
		}
	}

	public Set<String> getUrlPatterns(String servletName)
	{
		return _urlPatterns.get(servletName);
	}

	/**
	 * Sets the default servlet. 4.
	 * 如果前三个规则都没有产生一个servlet匹配，容器将试图为请求资源提供相关的内容。如果应用中定义了一个“default”servlet，它将被使用。许多容器提供了一种隐式的default servlet用于提供内容。
	 */
	public void setDefaultServlet(ServletConfigImpl config) throws ServletException
	{
		_defaultServlet = config;
	}

	/**
	 * 查找 ServletInvocation 所匹配 的 Servlet,并返回生成的FilterChain
	 * 
	 * 用于映射到Servlet的路径是请求对象的请求URL减去上下文和路径参数部分。下面的URL路径映射规则按顺序使用。使用第一个匹配成功的且不会进一步尝试匹配：
	 * 
	 * 1. 容器将尝试找到一个请求路径到servlet路径的精确匹配。成功匹配则选择该servlet。
	 * 
	 * 2. 容器将递归地尝试匹配最长路径前缀。这是通过一次一个目录的遍历路径树完成的，使用‘/’字符作为路径分隔符。最长匹配确定选择的servlet。
	 * 
	 * 3. 如果URL最后一部分包含一个扩展名（如 .jsp），servlet容器将视图匹配为扩展名处理请求的Servlet。扩展名定义在最后一部分的最后一个‘.’字符之后。
	 * 
	 * 4. 如果前三个规则都没有产生一个servlet匹配，容器将试图为请求资源提供相关的内容。如果应用中定义了一个“default”servlet，它将被使用。许多容器提供了一种隐式的default servlet用于提供内容。
	 * 
	 * @param invocation
	 * @return
	 * @throws ServletException
	 */
	public FilterChain createServletChain(ServletInvocation invocation) throws ServletException
	{
		String contextURI = invocation.getContextURI();

		String servletName = null;

		ServletConfigImpl config = null;

		ArrayList<String> vars = new ArrayList<String>();

		// 1-2-3:查找与contextURI最佳匹配的Servlet
		config = mapServlet(contextURI);

		// 4:默认的Servlet(urlPattern为"/",当无法找到匹配的Servlet或jsp时,则默认匹配的Servlet)
		if (config == null && servletName == null)
		{
			config = _defaultServlet;

			vars.add(contextURI);
		}

		// 5:无法找到合适的Servlet,返回404
		if (config == null && servletName == null)
		{
			LOG.fine(L.l("'{0}' has no default servlet defined", contextURI));

			return new ErrorFilterChain(404);
		}

		String servletPath = contextURI; // TODO: how to decide servletPath ?

		invocation.setServletPath(servletPath);

		if (servletPath.length() < contextURI.length())
			invocation.setPathInfo(contextURI.substring(servletPath.length()));
		else
			invocation.setPathInfo(null);

		invocation.setServletName(servletName);

		if (LOG.isLoggable(Level.FINER))
		{
			LOG.finer(_webApp + " map (uri:" + contextURI + " -> " + servletName + ")");
		}

		// 创建FilterChain
		FilterChain chain;
		if (config != null)
			chain = _servletManager.createServletChain(config, invocation);
		else
			chain = _servletManager.createServletChain(servletName, invocation);

		// JSP
		/*
		 * if (chain instanceof PageFilterChain) { PageFilterChain pageChain = (PageFilterChain) chain;
		 * 
		 * chain = PrecompilePageFilterChain.create(invocation, pageChain); }
		 */

		return chain;
	}

	/**
	 * 查找与contextURI最佳匹配的Servlet Specification: Servlet-3_1-PFD chapter 12.1
	 * 
	 * @param contextURI
	 * @return
	 */
	public ServletConfigImpl mapServlet(String contextURI)
	{
		// Rule 1 -- Exact Match :查找是否存在URL精确匹配的<servler-mapping>
		if (_exactServletMappings.size() > 0)
		{
			ServletMapping servletMapping = _exactServletMappings.get(contextURI);

			if (servletMapping != null)
				return servletMapping.getServletConfig();
		}

		// Rule 2 -- Longest Prefix Match : 查找最长前缀匹配的Servlet,如:/page/today/123 会 匹配 /page/today/*,而非/page/*
		if (_prefixServletMappings.size() > 0)
		{
			String prefixPattern;
			ServletMapping servletMapping = null;

			for (Map.Entry<String, ServletMapping> entry : _prefixServletMappings.entrySet())
			{
				prefixPattern = entry.getKey();

				if (prefixPatternMatch(contextURI, prefixPattern))
				{
					servletMapping = entry.getValue();

					if (servletMapping != null)
						return servletMapping.getServletConfig();
				}
			}
		}

		// Rule 3 -- Extension Match : 像.do,.jsp等基于扩展名的匹配
		if (_extensionServletMappings.size() > 0)
		{
			String extensionPattern;
			ServletMapping servletMapping = null;

			for (Map.Entry<String, ServletMapping> entry : _extensionServletMappings.entrySet())
			{
				extensionPattern = entry.getKey();

				if (extensionPatternMatch(contextURI, extensionPattern))
				{
					servletMapping = entry.getValue();

					if (servletMapping != null)
						return servletMapping.getServletConfig();
				}
			}
		}

		return null;
	}

	@Deprecated
	public String getServletPattern(String uri)
	{

		Object value = null;

		if (_servletMappings != null)
			value = _servletMappings.map(uri);

		if (value != null)
			return uri;
		else
			return null;
	}



	/**
	 * Returns the servlet matching patterns.
	 */
	public ArrayList<String> getURLPatterns()
	{
		ArrayList<String> patterns = _servletMappings.getURLPatterns();

		return patterns;
	}

	public ServletMapping getServletMapping(String pattern)
	{
		return _exactServletMappings.get(pattern);
	}

	private void addServlet(String servletName) throws ServletException
	{
		if (_servletManager.getServlet(servletName) != null)
			return;

		ServletConfigImpl config = _webApp.createNewServletConfig();

		try
		{
			config.setServletClass(servletName);
		}
		catch (RuntimeException e)
		{
			throw e;
		}
		catch (Exception e)
		{
			throw new ServletException(e);
		}

		config.init();

		_servletManager.addServlet(config);
	}

	public void destroy()
	{
		_servletManager.destroy();
	}

	/**
	 * 
	 * 按String的长度倒序排列
	 * 
	 * @author KEN
	 * 
	 */
	private static class PatternLengthComparator implements Comparator<String>
	{
		@Override
		public int compare(String pattern1, String pattern2)
		{
			if (pattern1.length() > pattern2.length())
				return -1;
			else if (pattern1.length() == pattern2.length())
				return 0;
			else
				return 1;
		}
	}

	// util Method---------------------------------------------------------------------------

	/**
	 * 前缀匹配
	 * 
	 * @param requestPath
	 * @param prefixPattern
	 * @return
	 */
	private static boolean prefixPatternMatch(String requestPath, String prefixPattern)
	{

		if (prefixPattern == null)
			return (false);

		// Case 2 - Path Match ("/.../*")
		if (prefixPattern.equals("/*"))
			return (true);
		if (prefixPattern.endsWith("/*"))
		{
			if (prefixPattern.regionMatches(0, requestPath, 0, prefixPattern.length() - 2))
			{
				if (requestPath.length() == (prefixPattern.length() - 2))
				{
					return (true);
				}
				else if ('/' == requestPath.charAt(prefixPattern.length() - 2))
				{
					return (true);
				}
			}
			return (false);
		}

		return (false);
	}

	/**
	 * 
	 * @param requestPath
	 * @param extensionPattern
	 * @return
	 */
	private static boolean extensionPatternMatch(String requestPath, String extensionPattern)
	{

		if (extensionPattern == null)
			return (false);

		// Case 3 - Extension Match
		if (extensionPattern.startsWith("*."))
		{
			int slash = requestPath.lastIndexOf('/');
			int period = requestPath.lastIndexOf('.');
			if ((slash >= 0) && (period > slash) && (period != requestPath.length() - 1)
					&& ((requestPath.length() - period) == (extensionPattern.length() - 1)))
			{
				return (extensionPattern.regionMatches(2, requestPath, period + 1, extensionPattern.length() - 2));
			}
		}

		return (false);
	}

}
