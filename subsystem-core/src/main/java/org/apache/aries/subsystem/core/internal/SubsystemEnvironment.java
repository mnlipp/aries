/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aries.subsystem.core.internal;

import static org.apache.aries.application.utils.AppConstants.LOG_ENTRY;
import static org.apache.aries.application.utils.AppConstants.LOG_EXIT;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.aries.subsystem.core.Environment;
import org.apache.aries.subsystem.core.ResourceHelper;
import org.eclipse.equinox.region.Region;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wiring;
import org.osgi.service.repository.Repository;
import org.osgi.service.subsystem.Subsystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * TODO
 * The locating of providers for transitive dependencies needs to have subsystem type and share policies taken into account.
 * So does the locating of providers for feature content with respect to children of the first parent that is not a feature.
 */
public class SubsystemEnvironment implements Environment {
	private static final Logger logger = LoggerFactory.getLogger(SubsystemEnvironment.class);
	
//	private final Set<Resource> resources = new HashSet<Resource>();
	private final SharingPolicyValidator validator;
	private final AriesSubsystem subsystem;
	
	public SubsystemEnvironment(AriesSubsystem subsystem) throws IOException, URISyntaxException {
		this.subsystem = subsystem;
		Region regionTo = subsystem.getRegion();
		while (subsystem.getArchive().getSubsystemManifest()
				.getSubsystemTypeHeader().getProvisionPolicyDirective()
				.isRejectDependencies()) {
			subsystem = (AriesSubsystem) subsystem.getParents()
					.iterator().next();
		}
		Region regionFrom = subsystem.getRegion();
		validator = new SharingPolicyValidator(regionFrom, regionTo);
	}
	
	@Override
	public SortedSet<Capability> findProviders(Requirement requirement) {
		logger.debug(LOG_ENTRY, "findProviders", requirement);
		// TODO Need a more robust comparator. This is just a temporary place holder.
		SortedSet<Capability> capabilities = new TreeSet<Capability>(
				new Comparator<Capability>() {
					@Override
					public int compare(Capability capability1, Capability capability2) {
						if (logger.isDebugEnabled())
							logger.debug(LOG_ENTRY, "compare", new Object[]{capability1, capability2});
						int result = 0;
						boolean br1 = capability1.getResource() instanceof BundleRevision;
						boolean br2 = capability2.getResource() instanceof BundleRevision;
						if (br1 && !br2)
							result = -1;
						else if (!br1 && br2)
							result = 1;
						logger.debug(LOG_EXIT, "compare", result);
						return result;
					}
				});
		if (requirement instanceof OsgiIdentityRequirement) { 
			logger.debug("The requirement is an instance of OsgiIdentityRequirement");
			// TODO Consider returning only the first capability matched by the requirement in this case.
			OsgiIdentityRequirement identity = (OsgiIdentityRequirement)requirement;
			// Unscoped subsystems share content resources as well as transitive
			// dependencies. Scoped subsystems share transitive dependencies as
			// long as they're in the same region.
			if (subsystem.isFeature() || identity.isTransitiveDependency()) {
				findConstituentProviders(requirement, capabilities);
			}
			findArchiveProviders(capabilities, identity/*, !identity.isTransitiveDependency()*/);
			findRepositoryServiceProviders(capabilities, identity/*, !identity.isTransitiveDependency()*/);
		}
		else {
			logger.debug("The requirement is NOT an instance of OsgiIdentityRequirement");
			// This means we're looking for capabilities satisfying a requirement within a content resource or transitive dependency.
			findArchiveProviders(capabilities, requirement/*, false*/);
			findRepositoryServiceProviders(capabilities, requirement/*, false*/);
			// TODO The following is a quick fix to ensure this environment always returns capabilities provided by the system bundle. Needs some more thought.
			findConstituentProviders(requirement, capabilities);
		}
		logger.debug(LOG_EXIT, "findProviders", capabilities);
		return capabilities;
	}
	
	@Override
	public Map<Requirement, SortedSet<Capability>> findProviders(Collection<? extends Requirement> requirements) {
		logger.debug(LOG_ENTRY, "findProviders", requirements);
		Map<Requirement, SortedSet<Capability>> result = new HashMap<Requirement, SortedSet<Capability>>(requirements.size());
		for (Requirement requirement : requirements)
			result.put(requirement, findProviders(requirement));
		logger.debug(LOG_EXIT, "findProviders", result);
		return result;
	}
	
	public Resource findResource(OsgiIdentityRequirement requirement) {
		logger.debug(LOG_ENTRY, "findResource", requirement);
		Collection<Capability> capabilities = findProviders(requirement);
		Resource result = null;
		if (!capabilities.isEmpty())
			result = capabilities.iterator().next().getResource();
		logger.debug(LOG_EXIT, "findResource", result);
		return result;
	}

	@Override
	public Map<Resource, Wiring> getWirings() {
		logger.debug(LOG_ENTRY, "getWirings");
		Map<Resource, Wiring> result = new HashMap<Resource, Wiring>();
		BundleContext bundleContext = Activator.getInstance().getBundleContext().getBundle(0).getBundleContext();
		for (Bundle bundle : bundleContext.getBundles()) {
			BundleRevision revision = bundle.adapt(BundleRevision.class);
			Wiring wiring = revision.getWiring();
			if (wiring != null) {
				result.put(
						revision, 
						revision.getWiring());
			}
		}
		logger.debug(LOG_EXIT, "getWirings", result);
		return result;
	}
	
	public boolean isContentResource(Resource resource) {
//		logger.debug(LOG_ENTRY, "isContentResource", resource);
//		boolean result = resources.contains(resource);
//		logger.debug(LOG_EXIT, "isContentResource", result);
//		return result;
		return subsystem.getArchive().getSubsystemManifest().getSubsystemContentHeader().contains(resource);
	}

	@Override
	public boolean isEffective(Requirement requirement) {
		logger.debug(LOG_ENTRY, "isEffective", requirement);
		boolean result = true;
		logger.debug(LOG_EXIT, "isEffective", result);
		return true;
	}
	
	private void findConstituentProviders(Requirement requirement, Collection<Capability> capabilities) {
		if (logger.isDebugEnabled())
			logger.debug(LOG_ENTRY, "findConstituentProviders", new Object[]{requirement, capabilities});
		AriesSubsystem subsystem = this.subsystem;
		if (requirement instanceof OsgiIdentityRequirement) {
			// We only want to return providers from the same region as the subsystem.
			// Find the one and only one scoped subsystem in the region, which
			// will be either the current subsystem or one of its parents.
			do {
				subsystem = (AriesSubsystem)subsystem.getParents().iterator().next();
			} while (!(subsystem.isApplication() || subsystem.isComposite()));
			// Now search the one and only one scoped parent within the same region
			// and all children that are also in the same region for a provider.
			findConstituentProviders(subsystem, requirement, capabilities);
			return;
		}
		logger.debug("Navigating up the parent hierarchy...");
		while (!subsystem.getParents().isEmpty()) {
			subsystem = (AriesSubsystem)subsystem.getParents().iterator().next();
			logger.debug("Next parent is: {}", subsystem);
		}
		findConstituentProviders(subsystem, requirement, capabilities);
		logger.debug(LOG_EXIT, "findConstituentProviders");
	}
	
	private void findConstituentProviders(AriesSubsystem subsystem, Requirement requirement, Collection<Capability> capabilities) {
		if (logger.isDebugEnabled())
			logger.debug(LOG_ENTRY, "findConstituentProviders", new Object[]{subsystem, requirement, capabilities});
		// Because constituent providers are already provisioned resources, the
		// sharing policy check must be between the requiring subsystem and the
		// offering subsystem, not the subsystem the resource would be
		// provisioned to as in the other methods.
		SharingPolicyValidator validator = new SharingPolicyValidator(subsystem.getRegion(), this.subsystem.getRegion());
		for (Resource resource : subsystem.getConstituents()) {
			logger.debug("Evaluating resource: {}", resource);
			for (Capability capability : resource.getCapabilities(requirement.getNamespace())) {
				logger.debug("Evaluating capability: {}", capability);
				// Filter out capabilities offered by dependencies that will be
				// or already are provisioned to an out of scope region. This
				// filtering does not apply to osgi.identity requirements within
				// the same region.
				if (!(requirement instanceof OsgiIdentityRequirement) && !validator.isValid(capability))
					continue;
				if (ResourceHelper.matches(requirement, capability)) {
					logger.debug("Adding capability: {}", capability);
					capabilities.add(capability);
				}
			}
		}
		for (Subsystem child : subsystem.getChildren()) {
			logger.debug("Evaluating child subsystem: {}", child);
			// If the requirement is osgi.identity and the child is not in the
			// same region as the parent, we do not want to search it.
			if (requirement instanceof OsgiIdentityRequirement
					&& !subsystem.getRegion().equals(((AriesSubsystem)child).getRegion()))
				continue;
			findConstituentProviders((AriesSubsystem)child, requirement, capabilities);
		}
		logger.debug(LOG_EXIT, "findConstituentProviders");
	}
	
	private void findArchiveProviders(Collection<Capability> capabilities, Requirement requirement/*, boolean content*/) {
		if (logger.isDebugEnabled())
			logger.debug(LOG_ENTRY, "findArchiveProviders", new Object[]{capabilities, requirement/*, content*/});
		AriesSubsystem subsystem = this.subsystem;
		logger.debug("Navigating up the parent hierarchy...");
		while (!subsystem.getParents().isEmpty()) {
			subsystem = (AriesSubsystem)subsystem.getParents().iterator().next();
			logger.debug("Next parent is: {}", subsystem);
		}
		findArchiveProviders(capabilities, requirement, subsystem/*, content*/);
		logger.debug(LOG_EXIT, "findArchiveProviders");
	}
	
	private void findArchiveProviders(Collection<Capability> capabilities, Requirement requirement, AriesSubsystem subsystem/*, boolean content*/) {
		if (logger.isDebugEnabled())
			logger.debug(LOG_ENTRY, "findArchiveProviders", new Object[]{capabilities, requirement, subsystem/*, content*/});
		for (Capability capability : subsystem.getArchive().findProviders(requirement)) {
			logger.debug("Adding capability: {}", capability);
			// Filter out capabilities offered by dependencies that will be or
			// already are provisioned to an out of scope region.
			if (!requirement.getNamespace().equals(IdentityNamespace.IDENTITY_NAMESPACE) && !isContentResource(capability.getResource()) && !validator.isValid(capability))
				continue;
			capabilities.add(capability);
//			if (content) {
//				Resource resource = capability.getResource();
//				logger.debug("Adding content resource: {}", resource);
//				resources.add(resource);
//			}
		}
		findArchiveProviders(capabilities, requirement, subsystem.getChildren()/*, content*/);
		logger.debug(LOG_EXIT, "findArchiveProviders");
	}
	
	private void findArchiveProviders(Collection<Capability> capabilities, Requirement requirement, Collection<Subsystem> children/*, boolean content*/) {
		if (logger.isDebugEnabled())
			logger.debug(LOG_ENTRY, "findArchiveProviders", new Object[]{capabilities, requirement, children/*, content*/});
		for (Subsystem child : children) {
			logger.debug("Evaluating child subsystem: {}", child);
			findArchiveProviders(capabilities, requirement, (AriesSubsystem)child/*, content*/);
		}
		logger.debug(LOG_EXIT, "findArchiveProviders");
	}
	
	private void findRepositoryServiceProviders(Collection<Capability> capabilities, Requirement requirement/*, boolean content*/) {
		if (logger.isDebugEnabled())
			logger.debug(LOG_ENTRY, "findRepositoryServiceProviders", new Object[]{capabilities, requirement/*, content*/});
		Collection<Repository> repositories = Activator.getInstance().getServiceProvider().getServices(Repository.class);
		for (Repository repository : repositories) {
			logger.debug("Evaluating repository: {}", repository);
			Map<Requirement, Collection<Capability>> map = repository.findProviders(Arrays.asList(requirement));
			Collection<Capability> caps = map.get(requirement);
			if (caps != null) {
				for (Capability capability : caps) {
					logger.debug("Adding capability: {}", capability);
					// Filter out capabilities offered by dependencies that will be or
					// already are provisioned to an out of scope region.
					if (!requirement.getNamespace().equals(IdentityNamespace.IDENTITY_NAMESPACE) && !isContentResource(capability.getResource()) && !validator.isValid(capability))
						continue;
					capabilities.add(capability);
//					if (content) {
//						Resource resource = capability.getResource();
//						logger.debug("Adding content resource: {}", resource);
//						resources.add(resource);
//					}
				}
			}
		}
		logger.debug(LOG_EXIT, "findRepositoryServiceProviders");
	}
}