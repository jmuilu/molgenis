package org.molgenis.compute.ui.workflow;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.molgenis.compute.ui.ComputeUiException;
import org.molgenis.compute.ui.IdGenerator;
import org.molgenis.compute.ui.meta.UIParameterMetaData;
import org.molgenis.compute.ui.meta.UIWorkflowMetaData;
import org.molgenis.compute.ui.meta.UIWorkflowNodeMetaData;
import org.molgenis.compute.ui.meta.UIWorkflowParameterMetaData;
import org.molgenis.compute.ui.meta.UIWorkflowProtocolMetaData;
import org.molgenis.compute.ui.model.ParameterType;
import org.molgenis.compute.ui.model.UIParameter;
import org.molgenis.compute.ui.model.UIWorkflow;
import org.molgenis.compute.ui.model.UIWorkflowNode;
import org.molgenis.compute.ui.model.UIWorkflowParameter;
import org.molgenis.compute.ui.model.UIWorkflowProtocol;
import org.molgenis.compute5.ComputeProperties;
import org.molgenis.compute5.model.Input;
import org.molgenis.compute5.model.Output;
import org.molgenis.compute5.model.Step;
import org.molgenis.compute5.model.Workflow;
import org.molgenis.compute5.parsers.WorkflowCsvParser;
import org.molgenis.data.AttributeMetaData;
import org.molgenis.data.DataService;
import org.molgenis.data.Entity;
import org.molgenis.data.csv.CsvRepository;
import org.molgenis.data.support.QueryImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

@Component
public class WorkflowImportService
{
	private static Logger logger = Logger.getLogger(WorkflowImportService.class);
	private final DataService dataService;

	@Autowired
	public WorkflowImportService(DataService dataService)
	{
		this.dataService = dataService;
	}

	@Transactional
	public void importWorkflow(ComputeProperties computeProperties) throws IOException
	{
		String baseDir = computeProperties.path;
		if (!new File(baseDir).exists()) throw new IOException("Directory '" + baseDir + "' does not exist.");

		String workflowFileName = Paths.get(baseDir, computeProperties.workFlow).toString();
		if (!new File(workflowFileName).exists()) throw new IOException("Workflow file '" + workflowFileName
				+ "' does not exist.");

		String workflowName = Paths.get(baseDir).getFileName().toString();
		if (workflowExists(workflowName)) throw new ComputeUiException("Workflow '" + workflowName
				+ "' already exists.");

		File parameterFile = new File(baseDir, computeProperties.parameters[0]);
		if (!parameterFile.exists()) throw new IOException("Parameters file '" + computeProperties.parameters[0]
				+ "' does not exist.");

		logger.info("Importing pipeline '" + workflowName + "'");

		Workflow workflow = new WorkflowCsvParser().parse(workflowFileName, computeProperties);

		Map<String, UIWorkflowNode> nodesByName = Maps.newLinkedHashMap();
		for (Step step : workflow.getSteps())
		{
			UIWorkflowProtocol protocol = dataService.findOne(UIWorkflowProtocolMetaData.INSTANCE.getName(),
					new QueryImpl().eq(UIWorkflowProtocolMetaData.NAME, step.getProtocol().getName()),
					UIWorkflowProtocol.class);

			if (protocol == null)
			{
				protocol = new UIWorkflowProtocol(IdGenerator.generateId(), step.getProtocol().getName(), step
						.getProtocol().getTemplate());

				List<UIParameter> parameters = Lists.newArrayList();

				for (Input input : step.getProtocol().getInputs())
				{
					UIParameter parameter = new UIParameter(IdGenerator.generateId(), input.getName());
					parameter.setType(ParameterType.INPUT);
					parameter.setDataType(input.getType());
					parameters.add(parameter);
				}

				for (Output output : step.getProtocol().getOutputs())
				{
					UIParameter parameter = new UIParameter(IdGenerator.generateId(), output.getName());
					parameter.setType(ParameterType.OUTPUT);
					parameter.setDataType(output.getType());
					parameters.add(parameter);
				}

				dataService.add(UIParameterMetaData.INSTANCE.getName(), parameters);
				protocol.setParameters(parameters);

				dataService.add(UIWorkflowProtocolMetaData.INSTANCE.getName(), protocol);
			}

			UIWorkflowNode node = new UIWorkflowNode(IdGenerator.generateId(), step.getName(), protocol);
			dataService.add(UIWorkflowNodeMetaData.INSTANCE.getName(), node);

			nodesByName.put(step.getName(), node);
		}

		for (Step step : workflow.getSteps())
		{
			if (!step.getPreviousSteps().isEmpty())
			{
				UIWorkflowNode node = nodesByName.get(step.getName());
				for (String prevStepName : step.getPreviousSteps())
				{
					node.addPreviousNode(nodesByName.get(prevStepName));
				}
			}
		}

		dataService.update(UIWorkflowNodeMetaData.INSTANCE.getName(), nodesByName.values());

		List<UIWorkflowParameter> uiWorkflowParameters = parseParametersFile(parameterFile);
		dataService.add(UIWorkflowParameterMetaData.INSTANCE.getName(), uiWorkflowParameters);

		UIWorkflow uiWorkflow = new UIWorkflow(IdGenerator.generateId(), workflowName);
		uiWorkflow.setNodes(Lists.newArrayList(nodesByName.values()));
		uiWorkflow.setGenerateScript(computeProperties.customSubmit);// ????
		uiWorkflow.setParameters(uiWorkflowParameters);

		// TODO remove TEST code
		Entity target = dataService.findOne("entities", "Script");
		uiWorkflow.setTargetType(target);

		dataService.add(UIWorkflowMetaData.INSTANCE.getName(), uiWorkflow);

		logger.info("Import pipeline '" + workflowName + "' done.");
	}

	private boolean workflowExists(String name)
	{
		return dataService.findOne(UIWorkflowMetaData.INSTANCE.getName(),
				new QueryImpl().eq(UIWorkflowMetaData.NAME, name)) != null;
	}

	private List<UIWorkflowParameter> parseParametersFile(File f)
	{
		if (!f.getName().toLowerCase().endsWith(".csv")) throw new ComputeUiException(
				"Parameters file must be a csv file.");

		List<UIWorkflowParameter> params = Lists.newArrayList();
		CsvRepository csv = new CsvRepository(f, null);
		try
		{
			Entity e = csv.iterator().next();

			for (AttributeMetaData attr : csv.getEntityMetaData().getAttributes())
			{
				params.add(new UIWorkflowParameter(IdGenerator.generateId(), attr.getName(),
						e.getString(attr.getName())));
			}
		}
		finally
		{
			IOUtils.closeQuietly(csv);
		}

		return params;
	}
}