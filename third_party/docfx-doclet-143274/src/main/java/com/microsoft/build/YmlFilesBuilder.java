package com.microsoft.build;

import com.microsoft.lookup.ClassItemsLookup;
import com.microsoft.lookup.ClassLookup;
import com.microsoft.lookup.PackageLookup;
import com.microsoft.model.MetadataFile;
import com.microsoft.model.MetadataFileItem;
import com.microsoft.model.TocFile;
import com.microsoft.model.TocItem;
import com.microsoft.model.TocTypeMap;
import com.microsoft.util.ElementUtil;
import com.microsoft.util.FileUtil;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.tools.Diagnostic.Kind;
import jdk.javadoc.doclet.DocletEnvironment;

import javax.lang.model.element.PackageElement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import jdk.javadoc.doclet.Reporter;

import static com.microsoft.build.BuilderUtil.populateUidValues;

public class YmlFilesBuilder {
    private DocletEnvironment environment;
    private String outputPath;
    private ElementUtil elementUtil;
    private PackageLookup packageLookup;
    private String projectName;
    private boolean disableChangelog;
    private ProjectBuilder projectBuilder;
    private PackageBuilder packageBuilder;
    private ClassBuilder classBuilder;
    private ReferenceBuilder referenceBuilder;
    private Reporter reporter;
    private ExecutorService executorService;
    private int numThreads;

    public YmlFilesBuilder(DocletEnvironment environment, String outputPath,
                           String[] excludePackages, String[] excludeClasses, String projectName, boolean disableChangelog, int numThreads, Reporter reporter) {
        this.executorService = Executors.newFixedThreadPool(numThreads);
        this.numThreads = numThreads;
        this.environment = environment;
        this.outputPath = outputPath;
        this.elementUtil = new ElementUtil(excludePackages, excludeClasses);
        this.packageLookup = new PackageLookup(environment);
        this.projectName = projectName;
        this.disableChangelog = disableChangelog;
        this.projectBuilder = new ProjectBuilder(projectName);
        ClassLookup classLookup = new ClassLookup(environment);
        this.referenceBuilder = new ReferenceBuilder(environment, classLookup, elementUtil);
        this.packageBuilder = new PackageBuilder(packageLookup, outputPath, referenceBuilder);
        this.reporter = reporter;
        this.classBuilder = new ClassBuilder(elementUtil, classLookup, new ClassItemsLookup(environment), outputPath, referenceBuilder, executorService);
    }

    public boolean build() {
        boolean isConcurrent = numThreads == 1;
        //  table of contents
        TocFile tocFile = new TocFile(outputPath, projectName, disableChangelog);
        //  overview page
        MetadataFile projectMetadataFile = new MetadataFile(outputPath, "overview.yml");
        //  package summary pages
        List<MetadataFile> packageMetadataFiles;
        //  packages
        List<MetadataFileItem> packageItems;
        //  class/enum/interface/etc. pages
        List<MetadataFile> classMetadataFiles;

        if (isConcurrent) {
            packageMetadataFiles = Collections.synchronizedList(new ArrayList<>());
            packageItems = Collections.synchronizedList(new ArrayList<>());
            classMetadataFiles = Collections.synchronizedList(new ArrayList<>());
        } else {
            packageMetadataFiles = new ArrayList<>();
            packageItems = new ArrayList<>();
            classMetadataFiles = new ArrayList<>();
        }

        List<PackageElement> packageElementList = elementUtil.extractPackageElements(environment.getIncludedElements());
        for (PackageElement packageElement : packageElementList) {
            String packageUid = packageLookup.extractUid(packageElement);
            String packageStatus = packageLookup.extractStatus(packageElement);
            TocItem packageTocItem;
            if (isConcurrent) {
                packageTocItem = new TocItem(packageUid, packageUid, packageStatus, Collections.synchronizedList(new ArrayList<>()));
            } else {
                packageTocItem = new TocItem(packageUid, packageUid, packageStatus, new ArrayList<>());
            }
            //  build package summary
            packageMetadataFiles.add(packageBuilder.buildPackageMetadataFile(packageElement));
            // add package summary to toc
            packageTocItem.getItems().add(new TocItem(packageUid, "Package summary"));
            tocFile.addTocItem(packageTocItem);

            // build classes/interfaces/enums/exceptions/annotations
            TocTypeMap typeMap = new TocTypeMap();

            if (numThreads == 1) {
                classBuilder.buildFilesForInnerClasses(packageElement, typeMap, classMetadataFiles);
            } else {
                classBuilder.buildFilesForInnerClassesConcurrent(packageElement, typeMap, classMetadataFiles);
            }
            packageTocItem.getItems().addAll(joinTocTypeItems(typeMap));
        }

        executorService.shutdown();
        try {
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            reporter.print(Kind.ERROR, e.getMessage());
        }

        for (MetadataFile packageFile : packageMetadataFiles) {
            packageItems.addAll(packageFile.getItems());
            String packageFileName = packageFile.getFileName();
            for (MetadataFile classFile : classMetadataFiles) {
                String classFileName = classFile.getFileName();
                if (packageFileName.equalsIgnoreCase(classFileName)) {
                    packageFile.setFileName(packageFileName.replaceAll("\\.yml$", "(package).yml"));
                    classFile.setFileName(classFileName.replaceAll("\\.yml$", "(class).yml"));
                    break;
                }
            }
        }
        // build project summary page
        projectBuilder.buildProjectMetadataFile(packageItems, projectMetadataFile);

        // post-processing
        populateUidValues(packageMetadataFiles, classMetadataFiles);
        referenceBuilder.updateExternalReferences(classMetadataFiles);

        //  write to yaml files
        FileUtil.dumpToFile(projectMetadataFile);
        packageMetadataFiles.forEach(FileUtil::dumpToFile);
        classMetadataFiles.forEach(FileUtil::dumpToFile);
        FileUtil.dumpToFile(tocFile);

        return true;
    }

    List<TocItem> joinTocTypeItems(TocTypeMap tocTypeMap) {
        return tocTypeMap.getTitleList().stream()
                .filter(kindTitle -> tocTypeMap.get(kindTitle.getElementKind()).size() > 0)
                .flatMap(kindTitle -> {
                    tocTypeMap.get(kindTitle.getElementKind()).add(0, new TocItem(kindTitle.getTitle()));
                    return tocTypeMap.get(kindTitle.getElementKind()).stream();
                }).collect(Collectors.toList());
    }
}
