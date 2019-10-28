package core.ast.decomposition.cfg;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiVariable;
import core.ast.AbstractMethodDeclaration;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class PDGObjectSliceUnion {
    private PDG pdg;
    private AbstractMethodDeclaration method;
    private BasicBlock boundaryBlock;
    private Set<PDGNode> allNodeCriteria;
    private AbstractVariable objectReference;
    private PsiFile iFile;
    private int methodSize;
    private PDGSlice subgraph;
    private Set<PDGNode> sliceNodes;
    private Set<AbstractVariable> passedParameters;
    private Set<PDGNode> indispensableNodes;
    private Set<PDGNode> removableNodes;

    PDGObjectSliceUnion(PDG pdg, BasicBlock boundaryBlock, Set<PDGNode> allNodeCriteria, PlainVariable objectReference) {
        this.pdg = pdg;
        this.subgraph = new PDGSlice(pdg, boundaryBlock);
        this.sliceNodes = new TreeSet<>();
        for (PDGNode nodeCriterion : allNodeCriteria) {
            sliceNodes.addAll(subgraph.computeSlice(nodeCriterion));
        }
        this.method = pdg.getMethod();
        this.iFile = pdg.getPsiFile();
        this.methodSize = pdg.getTotalNumberOfStatements();
        this.boundaryBlock = boundaryBlock;
        this.allNodeCriteria = allNodeCriteria;
        this.objectReference = objectReference;
        //add any required object-state slices that may be used from the resulting slice
        Set<PDGNode> nodesToBeAddedToSliceDueToDependenceOnObjectStateSlices = new TreeSet<>();
        Set<PlainVariable> alreadyExaminedObjectReferences = new LinkedHashSet<>();
        for (PDGNode sliceNode : sliceNodes) {
            Set<AbstractVariable> usedVariables = sliceNode.usedVariables;
            for (AbstractVariable usedVariable : usedVariables) {
                if (usedVariable instanceof PlainVariable) {
                    PlainVariable plainVariable = (PlainVariable) usedVariable;
                    if (!alreadyExaminedObjectReferences.contains(plainVariable)
                            && !objectReference.equals(plainVariable)) {
                        Map<CompositeVariable, LinkedHashSet<PDGNode>> definedAttributeNodeCriteriaMap =
                                pdg.getDefinedAttributesOfReference(plainVariable);
                        if (!definedAttributeNodeCriteriaMap.isEmpty()) {
                            TreeSet<PDGNode> objectSlice = new TreeSet<>();
                            for (CompositeVariable compositeVariable : definedAttributeNodeCriteriaMap.keySet()) {
                                Set<PDGNode> nodeCriteria2 = definedAttributeNodeCriteriaMap.get(compositeVariable);
                                for (PDGNode nodeCriterion : nodeCriteria2) {
                                    if (subgraph.nodeBelongsToBlockBasedRegion(nodeCriterion))
                                        objectSlice.addAll(subgraph.computeSlice(nodeCriterion));
                                }
                            }
                            nodesToBeAddedToSliceDueToDependenceOnObjectStateSlices.addAll(objectSlice);
                        }
                        alreadyExaminedObjectReferences.add(plainVariable);
                    }
                }
            }
        }
        sliceNodes.addAll(nodesToBeAddedToSliceDueToDependenceOnObjectStateSlices);
        Set<PDGNode> throwStatementNodes = getThrowStatementNodesWithinRegion();
        Set<PDGNode> nodesToBeAddedToSliceDueToThrowStatementNodes = new TreeSet<>();
        for (PDGNode throwNode : throwStatementNodes) {
            for (PDGNode sliceNode : sliceNodes) {
                if (sliceNode instanceof PDGControlPredicateNode && isNestedInside(throwNode, sliceNode)) {
                    Set<PDGNode> throwNodeSlice = subgraph.computeSlice(throwNode);
                    nodesToBeAddedToSliceDueToThrowStatementNodes.addAll(throwNodeSlice);
                    break;
                }
            }
        }
        sliceNodes.addAll(nodesToBeAddedToSliceDueToThrowStatementNodes);
        Set<PDGNode> remainingNodes = new TreeSet<>();
        remainingNodes.add(pdg.getEntryNode());
        for (GraphNode node : pdg.nodes) {
            PDGNode pdgNode = (PDGNode) node;
            if (!sliceNodes.contains(pdgNode))
                remainingNodes.add(pdgNode);
        }
        Set<PDGNode> throwStatementNodesToBeAddedToDuplicatedNodesDueToRemainingNodes = new TreeSet<>();
        for (PDGNode throwNode : throwStatementNodes) {
            for (PDGNode remainingNode : remainingNodes) {
                if (remainingNode.getId() != 0 && isNestedInside(throwNode, remainingNode)) {
                    throwStatementNodesToBeAddedToDuplicatedNodesDueToRemainingNodes.add(throwNode);
                    break;
                }
            }
        }
        this.passedParameters = new LinkedHashSet<>();
        Set<PDGNode> nCD = new LinkedHashSet<>();
        Set<PDGNode> nDD = new LinkedHashSet<>();
        for (GraphEdge edge : pdg.edges) {
            PDGDependence dependence = (PDGDependence) edge;
            PDGNode srcPDGNode = (PDGNode) dependence.src;
            PDGNode dstPDGNode = (PDGNode) dependence.dst;
            if (dependence instanceof PDGDataDependence) {
                PDGDataDependence dataDependence = (PDGDataDependence) dependence;
                if (remainingNodes.contains(srcPDGNode) && sliceNodes.contains(dstPDGNode))
                    passedParameters.add(dataDependence.getData());
                if (sliceNodes.contains(srcPDGNode) && remainingNodes.contains(dstPDGNode) &&
                        !dataDependence.getData().equals(objectReference) && !dataDependence.getData().isField())
                    nDD.add(srcPDGNode);
            } else if (dependence instanceof PDGControlDependence) {
                if (sliceNodes.contains(srcPDGNode) && remainingNodes.contains(dstPDGNode))
                    nCD.add(srcPDGNode);
            }
        }
        Set<PDGNode> controlIndispensableNodes = new LinkedHashSet<>();
        for (PDGNode p : nCD) {
            for (AbstractVariable usedVariable : p.usedVariables) {
                Set<PDGNode> pSliceNodes = subgraph.computeSlice(p, usedVariable);
                for (GraphNode node : pdg.nodes) {
                    PDGNode q = (PDGNode) node;
                    if (pSliceNodes.contains(q) || q.equals(p))
                        controlIndispensableNodes.add(q);
                }
            }
            if (p.usedVariables.isEmpty()) {
                Set<PDGNode> pSliceNodes = subgraph.computeSlice(p);
                for (GraphNode node : pdg.nodes) {
                    PDGNode q = (PDGNode) node;
                    if (pSliceNodes.contains(q) || q.equals(p))
                        controlIndispensableNodes.add(q);
                }
            }
        }
        Set<PDGNode> dataIndispensableNodes = new LinkedHashSet<>();
        for (PDGNode p : nDD) {
            for (AbstractVariable definedVariable : p.definedVariables) {
                Set<PDGNode> pSliceNodes = subgraph.computeSlice(p, definedVariable);
                for (GraphNode node : pdg.nodes) {
                    PDGNode pdgNode = (PDGNode) node;
                    if (pSliceNodes.contains(pdgNode))
                        dataIndispensableNodes.add(pdgNode);
                }
            }
        }
        this.indispensableNodes = new TreeSet<>();
        indispensableNodes.addAll(controlIndispensableNodes);
        indispensableNodes.addAll(dataIndispensableNodes);
        Set<PDGNode> throwStatementNodesToBeAddedToDuplicatedNodesDueToIndispensableNodes = new TreeSet<>();
        for (PDGNode throwNode : throwStatementNodes) {
            for (PDGNode indispensableNode : indispensableNodes) {
                if (isNestedInside(throwNode, indispensableNode)) {
                    throwStatementNodesToBeAddedToDuplicatedNodesDueToIndispensableNodes.add(throwNode);
                    break;
                }
            }
        }
        for (PDGNode throwNode : throwStatementNodesToBeAddedToDuplicatedNodesDueToRemainingNodes) {
            indispensableNodes.addAll(subgraph.computeSlice(throwNode));
        }
        for (PDGNode throwNode : throwStatementNodesToBeAddedToDuplicatedNodesDueToIndispensableNodes) {
            indispensableNodes.addAll(subgraph.computeSlice(throwNode));
        }
        this.removableNodes = new LinkedHashSet<>();
        for (GraphNode node : pdg.nodes) {
            PDGNode pdgNode = (PDGNode) node;
            if (!remainingNodes.contains(pdgNode) && !indispensableNodes.contains(pdgNode))
                removableNodes.add(pdgNode);
        }
        for (PDGNode node : sliceNodes) {
            if (!(node instanceof PDGTryNode)) {
                if (node.declaresLocalVariable(objectReference) ||
                        ((objectReference.isField() || objectReference.isParameter()) &&
                                node.instantiatesLocalVariable(objectReference) && node.definesLocalVariable(objectReference))) {
                    removableNodes.add(node);
                    indispensableNodes.remove(node);
                    break;
                }
            }
        }
    }

    private boolean isNestedInside(PDGNode nestedNode, PDGNode parentNode) {
        for (GraphEdge edge : nestedNode.incomingEdges) {
            PDGDependence dependence = (PDGDependence) edge;
            if (dependence instanceof PDGControlDependence) {
                PDGControlDependence controlDependence = (PDGControlDependence) dependence;
                PDGNode srcPDGNode = (PDGNode) controlDependence.src;
                if (srcPDGNode.equals(parentNode))
                    return true;
                else
                    return isNestedInside(srcPDGNode, parentNode);
            }
        }
        return false;
    }

    private Set<PDGNode> getThrowStatementNodesWithinRegion() {
        Set<PDGNode> throwNodes = new LinkedHashSet<>();
        for (GraphNode node : subgraph.nodes) {
            PDGNode pdgNode = (PDGNode) node;
            if (pdgNode.getCFGNode() instanceof CFGThrowNode) {
                throwNodes.add(pdgNode);
            }
        }
        return throwNodes;
    }

    public Set<PsiVariable> getVariableDeclarationsAndAccessedFieldsInMethod() {
        return pdg.getVariableDeclarationsAndAccessedFieldsInMethod();
    }

    public AbstractMethodDeclaration getMethod() {
        return method;
    }

    public PsiFile getIFile() {
        return iFile;
    }

    public int getMethodSize() {
        return methodSize;
    }

    public BasicBlock getBoundaryBlock() {
        return boundaryBlock;
    }

    public PDGNode getExtractedMethodInvocationInsertionNode() {
        return ((TreeSet<PDGNode>) sliceNodes).first();
    }

    public AbstractVariable getObjectReference() {
        return objectReference;
    }

    public Set<PDGNode> getSliceNodes() {
        return this.sliceNodes;
    }

    public Set<AbstractVariable> getPassedParameters() {
        return passedParameters;
    }

    public Set<PDGNode> getRemovableNodes() {
        return removableNodes;
    }

    PDGNode getDeclarationOfObjectReference() {
        for (PDGNode pdgNode : sliceNodes) {
            if (pdgNode.declaresLocalVariable(objectReference))
                return pdgNode;
        }
        return null;
    }

    boolean declarationOfObjectReferenceBelongsToSliceNodes() {
        for (PDGNode node : sliceNodes) {
            if (node.declaresLocalVariable(objectReference))
                return true;
        }
        return false;
    }

    boolean declarationOfObjectReferenceBelongsToRemovableNodes() {
        for (PDGNode node : removableNodes) {
            if (node.declaresLocalVariable(objectReference))
                return true;
        }
        return false;
    }

    private boolean sliceContainsDeclaration(AbstractVariable variableDeclaration) {
        for (PDGNode node : sliceNodes) {
            if (node.declaresLocalVariable(variableDeclaration))
                return true;
        }
        return false;
    }

    private boolean allNodeCriteriaAreDuplicated() {
        Set<PDGNode> duplicatedNodes = new LinkedHashSet<>(sliceNodes);
        duplicatedNodes.retainAll(indispensableNodes);
        for (PDGNode nodeCriterion : allNodeCriteria) {
            if (!duplicatedNodes.contains(nodeCriterion))
                return false;
        }
        return true;
    }

    private boolean returnStatementIsControlDependentOnSliceNode() {
        for (GraphNode node : pdg.nodes) {
            PDGNode pdgNode = (PDGNode) node;
            if (pdgNode.getCFGNode() instanceof CFGExitNode) {
                if (isControlDependentOnSliceNode(pdgNode))
                    return true;
                if (sliceNodes.contains(pdgNode))
                    return true;
            }
        }
        return false;
    }

    private boolean isControlDependentOnSliceNode(PDGNode node) {
        for (GraphEdge edge : node.incomingEdges) {
            PDGDependence dependence = (PDGDependence) edge;
            if (dependence instanceof PDGControlDependence) {
                PDGControlDependence controlDependence = (PDGControlDependence) dependence;
                PDGNode srcPDGNode = (PDGNode) controlDependence.src;
                if (sliceNodes.contains(srcPDGNode))
                    return true;
                else
                    return isControlDependentOnSliceNode(srcPDGNode);
            }
        }
        return false;
    }

    private boolean nonDuplicatedSliceNodeAntiDependsOnNonRemovableNode() {
        Set<PDGNode> duplicatedNodes = new LinkedHashSet<>(sliceNodes);
        duplicatedNodes.retainAll(indispensableNodes);
        for (PDGNode sliceNode : sliceNodes) {
            if (!duplicatedNodes.contains(sliceNode)) {
                for (GraphEdge edge : sliceNode.incomingEdges) {
                    PDGDependence dependence = (PDGDependence) edge;
                    if (subgraph.edgeBelongsToBlockBasedRegion(dependence) && dependence instanceof PDGAntiDependence) {
                        PDGAntiDependence antiDependence = (PDGAntiDependence) dependence;
                        PDGNode srcPDGNode = (PDGNode) antiDependence.src;
                        if (!removableNodes.contains(srcPDGNode)
                                && !nodeDependsOnNonRemovableNode(srcPDGNode, antiDependence.getData()))
                            return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean nodeDependsOnNonRemovableNode(PDGNode node, AbstractVariable variable) {
        for (GraphEdge edge : node.incomingEdges) {
            PDGDependence dependence = (PDGDependence) edge;
            if (subgraph.edgeBelongsToBlockBasedRegion(dependence) && dependence instanceof PDGDataDependence) {
                PDGDataDependence dataDependence = (PDGDataDependence) dependence;
                if (dataDependence.getData().equals(variable)) {
                    PDGNode srcPDGNode = (PDGNode) dataDependence.src;
                    if (!removableNodes.contains(srcPDGNode))
                        return true;
                }
            }
        }
        return false;
    }

    private boolean nonDuplicatedSliceNodeOutputDependsOnNonRemovableNode() {
        Set<PDGNode> duplicatedNodes = new LinkedHashSet<>(sliceNodes);
        duplicatedNodes.retainAll(indispensableNodes);
        for (PDGNode sliceNode : sliceNodes) {
            if (!duplicatedNodes.contains(sliceNode)) {
                for (GraphEdge edge : sliceNode.incomingEdges) {
                    PDGDependence dependence = (PDGDependence) edge;
                    if (subgraph.edgeBelongsToBlockBasedRegion(dependence) && dependence instanceof PDGOutputDependence) {
                        PDGOutputDependence outputDependence = (PDGOutputDependence) dependence;
                        PDGNode srcPDGNode = (PDGNode) outputDependence.src;
                        if (!removableNodes.contains(srcPDGNode))
                            return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean duplicatedSliceNodeWithClassInstantiationHasDependenceOnRemovableNode() {
        Set<PDGNode> duplicatedNodes = new LinkedHashSet<>(sliceNodes);
        duplicatedNodes.retainAll(indispensableNodes);
        for (PDGNode duplicatedNode : duplicatedNodes) {
            if (duplicatedNode.containsClassInstanceCreation()) {
                Map<PsiVariable, PsiNewExpression> classInstantiations = duplicatedNode.getClassInstantiations();
                for (PsiVariable variableDeclaration : classInstantiations.keySet()) {
                    for (GraphEdge edge : duplicatedNode.outgoingEdges) {
                        PDGDependence dependence = (PDGDependence) edge;
                        if (subgraph.edgeBelongsToBlockBasedRegion(dependence) && dependence != null) {
                            PDGNode dstPDGNode = (PDGNode) dependence.dst;
                            if (removableNodes.contains(dstPDGNode)) {
                                if (dstPDGNode.changesStateOfReference(variableDeclaration)
                                        || dstPDGNode.assignsReference(variableDeclaration)
                                        || dstPDGNode.accessesReference(variableDeclaration))
                                    return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean containsDuplicateNodeWithStateChangingMethodInvocation() {
        Set<PDGNode> duplicatedNodes = new LinkedHashSet<>(sliceNodes);
        duplicatedNodes.retainAll(indispensableNodes);
        for (PDGNode node : duplicatedNodes) {
            for (AbstractVariable stateChangingVariable : node.definedVariables) {
                if (stateChangingVariable instanceof CompositeVariable) {
                    PlainVariable plainVariable = stateChangingVariable.getInitialVariable();
                    if (!sliceContainsDeclaration(plainVariable))
                        return true;
                } else if (stateChangingVariable instanceof PlainVariable) {
                    PlainVariable plainVariable = stateChangingVariable.getInitialVariable();
                    if (plainVariable.isField())
                        return true;
                }
            }
        }
        return false;
    }

    private boolean objectSliceEqualsMethodBody() {
        int sliceSize = sliceNodes.size();
        if (sliceSize == methodSize)
            return true;
        else if (sliceSize == methodSize - 1) {
            TreeSet<GraphNode> nonIncludedInSliceMethodNodes = new TreeSet<>(pdg.nodes);
            nonIncludedInSliceMethodNodes.removeAll(sliceNodes);
            PDGNode pdgNode = (PDGNode) nonIncludedInSliceMethodNodes.first();
            return pdgNode instanceof PDGExitNode;
        }
        return false;
    }

    private boolean objectSliceHasMinimumSize() {
        int sliceSize = sliceNodes.size();
        if (sliceSize == 1)
            return true;
        else if (sliceSize == 2) {
            return sliceContainsDeclaration(objectReference);
        }
        return false;
    }

    private boolean objectReferenceIsReturnedVariableInOriginalMethod() {
        return pdg.getReturnedVariables().contains(objectReference);
    }

    private boolean declarationOfObjectReferenceIsDuplicated() {
        Set<PDGNode> duplicatedNodes = new LinkedHashSet<>(sliceNodes);
        duplicatedNodes.retainAll(indispensableNodes);
        for (PDGNode node : duplicatedNodes) {
            if (node.declaresLocalVariable(objectReference) && !(node instanceof PDGTryNode))
                return true;
        }
        return false;
    }

    private boolean sliceContainsReturnStatement() {
        for (PDGNode node : sliceNodes) {
            if (node.getCFGNode() instanceof CFGExitNode)
                return true;
        }
        return false;
    }

    private boolean sliceContainsBranchStatementWithoutInnermostLoop() {
        for (PDGNode node : sliceNodes) {
            CFGNode cfgNode = node.getCFGNode();
            if (cfgNode instanceof CFGBreakNode) {
                CFGBreakNode breakNode = (CFGBreakNode) cfgNode;
                CFGNode innerMostLoopNode = breakNode.getInnerMostLoopNode();
                if (innerMostLoopNode != null && !sliceNodes.contains(innerMostLoopNode.getPDGNode()))
                    return true;
            } else if (cfgNode instanceof CFGContinueNode) {
                CFGContinueNode continueNode = (CFGContinueNode) cfgNode;
                CFGNode innerMostLoopNode = continueNode.getInnerMostLoopNode();
                if (innerMostLoopNode != null && !sliceNodes.contains(innerMostLoopNode.getPDGNode()))
                    return true;
            }
        }
        return false;
    }

    private boolean complyWithUserThresholds() {
        int minimumSliceSize = 0;
        int maximumSliceSize = 1000;
        int maximumDuplication = 100;
        double maximumRatioOfDuplicatedToExtracted = 1.0;

        int sliceSize = sliceNodes.size();
        int duplicatedSize = sliceSize - removableNodes.size();
        double ratioOfDuplicatedToExtracted = (double) duplicatedSize / (double) sliceSize;

        if (sliceSize > (methodSize - maximumSliceSize))
            return false;
        if (duplicatedSize > maximumDuplication)
            return false;
        return !(ratioOfDuplicatedToExtracted > maximumRatioOfDuplicatedToExtracted);
    }

    private boolean variableCriterionIsStreamClosedInFinallyBlock() {
        //the declaration of the variable criterion is in the slice
        PDGNode declarationOfVariableCriterion = getDeclarationOfObjectReference();
        if (declarationOfVariableCriterion != null) {
            for (PDGNode sliceNode : sliceNodes) {
                if (sliceNode instanceof PDGTryNode) {
                    PDGTryNode tryNode = (PDGTryNode) sliceNode;
                    if (tryNode.hasFinallyClauseClosingVariable(objectReference)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean sliceContainsVariableDeclarationClosedInFinallyBlock() {
        for (PDGNode sliceNode : sliceNodes) {
            Set<AbstractVariable> declaredVariables = sliceNode.declaredVariables;
            for (AbstractVariable declaredVariable : declaredVariables) {
                for (PDGNode sliceNode2 : sliceNodes) {
                    if (sliceNode2 instanceof PDGTryNode) {
                        PDGTryNode tryNode = (PDGTryNode) sliceNode2;
                        if (tryNode.hasFinallyClauseClosingVariable(declaredVariable)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    boolean satisfiesRules() {
        return !objectSliceEqualsMethodBody() && !objectSliceHasMinimumSize() && !declarationOfObjectReferenceIsDuplicated() &&
                !objectReferenceIsReturnedVariableInOriginalMethod() &&
                !allNodeCriteriaAreDuplicated() && !returnStatementIsControlDependentOnSliceNode() && !sliceContainsReturnStatement() &&
                !containsDuplicateNodeWithStateChangingMethodInvocation() &&
                !nonDuplicatedSliceNodeAntiDependsOnNonRemovableNode() &&
                !nonDuplicatedSliceNodeOutputDependsOnNonRemovableNode() &&
                !duplicatedSliceNodeWithClassInstantiationHasDependenceOnRemovableNode() &&
                complyWithUserThresholds() && !sliceContainsBranchStatementWithoutInnermostLoop() &&
                !variableCriterionIsStreamClosedInFinallyBlock() && !sliceContainsVariableDeclarationClosedInFinallyBlock();
    }
}
