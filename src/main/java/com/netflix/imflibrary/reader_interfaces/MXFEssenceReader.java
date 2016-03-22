package com.netflix.imflibrary.reader_interfaces;


import com.netflix.imflibrary.IMFConstraints;
import com.netflix.imflibrary.IMFErrorLogger;
import com.netflix.imflibrary.IMFErrorLoggerImpl;
import com.netflix.imflibrary.KLVPacket;
import com.netflix.imflibrary.MXFOperationalPattern1A;
import com.netflix.imflibrary.exceptions.IMFException;
import com.netflix.imflibrary.exceptions.MXFException;
import com.netflix.imflibrary.st0377.HeaderPartition;
import com.netflix.imflibrary.st0377.PartitionPack;
import com.netflix.imflibrary.st0377.PrimerPack;
import com.netflix.imflibrary.st0377.RandomIndexPack;
import com.netflix.imflibrary.st0377.header.AudioChannelLabelSubDescriptor;
import com.netflix.imflibrary.st0377.header.InterchangeObject;
import com.netflix.imflibrary.st0377.header.SoundFieldGroupLabelSubDescriptor;
import com.netflix.imflibrary.st0377.header.SubDescriptor;
import com.netflix.imflibrary.st2067_2.CompositionPlaylist;
import com.netflix.imflibrary.utils.ByteArrayDataProvider;
import com.netflix.imflibrary.utils.ByteProvider;
import com.netflix.imflibrary.utils.FileDataProvider;
import com.netflix.imflibrary.utils.ResourceByteRangeProvider;
import com.netflix.imflibrary.writerTools.RegXMLLibHelper;
import com.netflix.imflibrary.writerTools.utils.IMFUUIDGenerator;
import com.sandflow.smpte.klv.Triplet;
import org.smpte_ra.schemas.st2067_2_2013.EssenceDescriptorBaseType;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Node;

import javax.annotation.Resource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MXFEssenceReader {

    private final IMFErrorLogger imfErrorLogger;
    private final ResourceByteRangeProvider resourceByteRangeProvider;
    private final File workingDirectory;
    /**
     * A constructor for the MXFEssenceReader object
     * @param resourceByteRangeProvider corresponding to the MXF essence, referred to as the essence in the rest of the documentation
     */
    public MXFEssenceReader(ResourceByteRangeProvider resourceByteRangeProvider){
        this.imfErrorLogger = new IMFErrorLoggerImpl();
        this.resourceByteRangeProvider = resourceByteRangeProvider;
        this.workingDirectory = new File(System.getProperty("user.dir") + File.separator + "MXFEssenceReaderWorkingDirectory");
    }

    /**
     * A method that returns the random index pack in the Essence
     * @return RandomIndexPack in the essence
     * @throws IOException - any I/O related error will be exposed through an IOException
     */
    public RandomIndexPack getRandomIndexPack()throws IOException {
        long archiveFileSize = this.resourceByteRangeProvider.getResourceSize();
        long randomIndexPackSize;
        {//logic to provide as an input stream the portion of the archive that contains randomIndexPack size
            long rangeEnd = archiveFileSize - 1;
            long rangeStart = archiveFileSize - 4;

            File fileWithRandomIndexPackSize = this.resourceByteRangeProvider.getByteRange(rangeStart, rangeEnd, this.workingDirectory);
            byte[] bytes = Files.readAllBytes(Paths.get(fileWithRandomIndexPackSize.toURI()));
            randomIndexPackSize = (long)(ByteBuffer.wrap(bytes).getInt());
        }

        RandomIndexPack randomIndexPack;
        {//logic to provide as an input stream the portion of the archive that contains randomIndexPack
            long rangeEnd = archiveFileSize - 1;
            long rangeStart = archiveFileSize - randomIndexPackSize;
            if (rangeStart < 0)
            {
                throw new MXFException(String.format("randomIndexPackSize = %d obtained from last 4 bytes of the MXF file is larger than archiveFile size = %d, implying that this file does not contain a RandomIndexPack",
                        randomIndexPackSize, archiveFileSize));
            }

            File fileWithRandomIndexPack = this.resourceByteRangeProvider.getByteRange(rangeStart, rangeEnd, this.workingDirectory);
            ByteProvider byteProvider = this.getByteProvider(fileWithRandomIndexPack);
            randomIndexPack = new RandomIndexPack(byteProvider, rangeStart, randomIndexPackSize);
        }

        return randomIndexPack;
    }

    /**
     * A method that returns an object model of the HeaderPartition in the Essence
     * @return Header partition in the essence
     * @throws IOException - any I/O related error will be exposed through an IOException
     */
    public HeaderPartition getHeaderPartition() throws IOException{
        IMFConstraints.HeaderPartitionIMF headerPartitionIMF = getHeaderPartitionIMF();
        return headerPartitionIMF.getHeaderPartitionOP1A().getHeaderPartition();
    }

    /**
     * A method that returns a list of partition packs in the Essence
     * @return List of partition packs in the essence
     * @throws IOException - any I/O related error will be exposed through an IOException
     */
    public List<PartitionPack> getPartitionPacks() throws IOException{
        RandomIndexPack randomIndexPack = getRandomIndexPack();

        List<PartitionPack> partitionPacks = new ArrayList<>();
        List<Long> allPartitionByteOffsets = randomIndexPack.getAllPartitionByteOffsets();
        for (long offset : allPartitionByteOffsets)
        {
            partitionPacks.add(getPartitionPack(offset));
        }

        //validate partition packs
        MXFOperationalPattern1A.checkOperationalPattern1ACompliance(partitionPacks);
        IMFConstraints.checkIMFCompliance(partitionPacks);
        return partitionPacks;
    }

    /**
     * A method that returns a list of EssenceDescriptor objects referenced by the Source Packages in the Essence
     *
     * @return List of DOM Nodes corresponding to every EssenceDescriptor in the essence
     * @throws IOException - any I/O related error will be exposed through an IOException
     */
    public List<Node> getEssenceDescriptorsDOMNodes() throws IOException{
        try {
            List<InterchangeObject.InterchangeObjectBO> essenceDescriptors = this.getHeaderPartition().getEssenceDescriptors();
            List<Node> essenceDescriptorNodes = new ArrayList<>();
            for (InterchangeObject.InterchangeObjectBO essenceDescriptor : essenceDescriptors) {
                KLVPacket.Header essenceDescriptorHeader = essenceDescriptor.getHeader();
                List<KLVPacket.Header> subDescriptorHeaders = this.getSubDescriptorKLVHeader(essenceDescriptor);
                /*Create a dom*/
                DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
                Document document = docBuilder.newDocument();

                DocumentFragment documentFragment = this.getEssenceDescriptorAsDocumentFragment(document, essenceDescriptorHeader, subDescriptorHeaders);
                Node node = documentFragment.getFirstChild();
                essenceDescriptorNodes.add(node);
            }
            return essenceDescriptorNodes;
        }
        catch(ParserConfigurationException e){
            throw new IMFException(e);
        }
    }

    /**
     * A method that returns a list of EssenceDescriptor objects referenced by the Source Packages in the Essence
     *
     * @return List of Object model representations corresponding to every EssenceDescriptor in the essence
     * @throws IOException - any I/O related error will be exposed through an IOException
     */
    public List<? extends InterchangeObject.InterchangeObjectBO> getEssenceDescriptors() throws IOException{
        return this.getHeaderPartition().getEssenceDescriptors();
    }

    /**
     * A method that returns the EssenceType corresponding to this Essence
     * @return a string representing either a MainImageSequence or a MainAudioSequence
     * @throws IOException - any I/O related error will be exposed through an IOException
     */
    public String getEssenceType() throws IOException{
        String result = "";
        HeaderPartition headerPartition = this.getHeaderPartitionIMF().getHeaderPartitionOP1A().getHeaderPartition();
        if(headerPartition.hasCDCIPictureEssenceDescriptor() || headerPartition.hasRGBAPictureEssenceDescriptor()){
            result = "MainImageSequence";
        }
        else if(headerPartition.hasWaveAudioEssenceDescriptor()){
            result = "MainAudioSequence";
        }
        return result;
    }

    /**
     * A method that returns the EssenceType enumeration corresponding to this Essence
     * @return a string representing either a MainImageSequence or a MainAudioSequence
     * @throws IOException - any I/O related error will be exposed through an IOException
     */
    public CompositionPlaylist.SequenceTypeEnum getEssenceTypeEnum() throws IOException{
        CompositionPlaylist.SequenceTypeEnum result = CompositionPlaylist.SequenceTypeEnum.Unknown;
        HeaderPartition headerPartition = this.getHeaderPartitionIMF().getHeaderPartitionOP1A().getHeaderPartition();
        if(headerPartition.hasCDCIPictureEssenceDescriptor() || headerPartition.hasRGBAPictureEssenceDescriptor()){
            result = CompositionPlaylist.SequenceTypeEnum.MainImageSequence;
        }
        else if(headerPartition.hasWaveAudioEssenceDescriptor()){
            result = CompositionPlaylist.SequenceTypeEnum.MainAudioSequence;
        }
        return result;
    }

    /**
     * A method that returns the spoken language within this essence provided it is an Audio Essence
     * @return string representing a spoken language as defined in RFC-5646
     */
    public String getAudioEssenceSpokenLanguage() throws IOException {
        String rfc5646SpokenLanguage = "";
        if(this.getEssenceType().equals("MainAudioSequence")){
            List<InterchangeObject> soundfieldGroupLabelSubDescriptors = this.getHeaderPartition().getSoundFieldGroupLabelSubDescriptors();
            for(InterchangeObject subDescriptor : soundfieldGroupLabelSubDescriptors){
                SoundFieldGroupLabelSubDescriptor soundFieldGroupLabelSubDescriptor = (SoundFieldGroupLabelSubDescriptor) subDescriptor;
                if(rfc5646SpokenLanguage.equals("")) {
                    rfc5646SpokenLanguage = soundFieldGroupLabelSubDescriptor.getRFC5646SpokenLanguage();
                }
                else if(!rfc5646SpokenLanguage.equals(soundFieldGroupLabelSubDescriptor.getRFC5646SpokenLanguage())){
                    throw new IMFException(String.format("Language Codes (%s, %s) do not match across the AudioChannelLabelSubDescriptors", rfc5646SpokenLanguage, soundFieldGroupLabelSubDescriptor.getRFC5646SpokenLanguage()));
                }
            }
        }
        else{
            throw new IMFException(String.format("Essences of type %s, do not have a spoken language", this.getEssenceType()));
        }
        return rfc5646SpokenLanguage;
    }

    private IMFConstraints.HeaderPartitionIMF getHeaderPartitionIMF() throws IOException {
        RandomIndexPack randomIndexPack = this.getRandomIndexPack();
        List<Long> allPartitionByteOffsets = randomIndexPack.getAllPartitionByteOffsets();
        long inclusiveRangeStart = allPartitionByteOffsets.get(0);
        long inclusiveRangeEnd = allPartitionByteOffsets.get(1) - 1;

        File fileWithHeaderPartition = this.resourceByteRangeProvider.getByteRange(inclusiveRangeStart, inclusiveRangeEnd, this.workingDirectory);
        ByteProvider byteProvider = this.getByteProvider(fileWithHeaderPartition);
        HeaderPartition headerPartition = new HeaderPartition(byteProvider, inclusiveRangeStart, inclusiveRangeEnd - inclusiveRangeStart + 1, this.imfErrorLogger);

        //validate header partition
        MXFOperationalPattern1A.HeaderPartitionOP1A headerPartitionOP1A = MXFOperationalPattern1A.checkOperationalPattern1ACompliance(headerPartition);
        IMFConstraints.HeaderPartitionIMF headerPartitionIMF = IMFConstraints.checkIMFCompliance(headerPartitionOP1A);
        return headerPartitionIMF;
    }


    private PartitionPack getPartitionPack(long resourceOffset) throws IOException
    {
        long archiveFileSize = this.resourceByteRangeProvider.getResourceSize();
        KLVPacket.Header header;
        {//logic to provide as an input stream the portion of the archive that contains PartitionPack KLVPacker Header
            long rangeEnd = resourceOffset +
                    (KLVPacket.KEY_FIELD_SIZE + KLVPacket.LENGTH_FIELD_SUFFIX_MAX_SIZE) -1;
            rangeEnd = rangeEnd < (archiveFileSize - 1) ? rangeEnd : (archiveFileSize - 1);

            File fileWithPartitionPackKLVPacketHeader = this.resourceByteRangeProvider.getByteRange(resourceOffset, rangeEnd, this.workingDirectory);
            ByteProvider byteProvider = this.getByteProvider(fileWithPartitionPackKLVPacketHeader);
            header = new KLVPacket.Header(byteProvider, resourceOffset);
        }

        PartitionPack partitionPack;
        {//logic to provide as an input stream the portion of the archive that contains a PartitionPack and next KLV header

            long rangeEnd = resourceOffset +
                    (KLVPacket.KEY_FIELD_SIZE + header.getLSize() + header.getVSize()) +
                    (KLVPacket.KEY_FIELD_SIZE + KLVPacket.LENGTH_FIELD_SUFFIX_MAX_SIZE) +
                    -1;
            rangeEnd = rangeEnd < (archiveFileSize - 1) ? rangeEnd : (archiveFileSize - 1);

            File fileWithPartitionPack = this.resourceByteRangeProvider.getByteRange(resourceOffset, rangeEnd, this.workingDirectory);
            ByteProvider byteProvider = this.getByteProvider(fileWithPartitionPack);
            partitionPack = new PartitionPack(byteProvider, resourceOffset, true);

        }

        return partitionPack;
    }

    private List<KLVPacket.Header> getSubDescriptorKLVHeader(InterchangeObject.InterchangeObjectBO essenceDescriptor) throws IOException {
        List<KLVPacket.Header> subDescriptorHeaders = new ArrayList<>();
        List<InterchangeObject.InterchangeObjectBO>subDescriptors = this.getHeaderPartition().getSubDescriptors(essenceDescriptor);
        for(InterchangeObject.InterchangeObjectBO subDescriptorBO : subDescriptors){
            if(subDescriptorBO != null) {
                subDescriptorHeaders.add(subDescriptorBO.getHeader());
            }
        }
        return subDescriptorHeaders;
    }

    private DocumentFragment getEssenceDescriptorAsDocumentFragment(Document document, KLVPacket.Header essenceDescriptor, List<KLVPacket.Header>subDescriptors) throws MXFException, IOException {
        document.setXmlStandalone(true);

        PrimerPack primerPack = this.getHeaderPartition().getPrimerPack();
        RegXMLLibHelper regXMLLibHelper = new RegXMLLibHelper(primerPack.getHeader(), this.getByteProvider(primerPack.getHeader()));
        Triplet essenceDescriptorTriplet = regXMLLibHelper.getTripletFromKLVHeader(essenceDescriptor, this.getByteProvider(essenceDescriptor));
        //DocumentFragment documentFragment = this.regXMLLibHelper.getDocumentFragment(essenceDescriptorTriplet, document);
        /*Get the Triplets corresponding to the SubDescriptors*/
        List<Triplet> subDescriptorTriplets = new ArrayList<>();
        for(KLVPacket.Header subDescriptorHeader : subDescriptors){
            subDescriptorTriplets.add(regXMLLibHelper.getTripletFromKLVHeader(subDescriptorHeader, this.getByteProvider(subDescriptorHeader)));
        }
        return regXMLLibHelper.getEssenceDescriptorDocumentFragment(essenceDescriptorTriplet, subDescriptorTriplets, document);
    }

    private KLVPacket.Header getPrimerPackHeader() throws IOException {
        return this.getHeaderPartition().getPrimerPack().getHeader();
    }

    private ByteProvider getByteProvider(KLVPacket.Header header) throws IOException {
        File file = this.resourceByteRangeProvider.getByteRange(header.getByteOffset(), header.getByteOffset() + header.getKLSize() + header.getVSize(), this.workingDirectory);
        return this.getByteProvider(file);
    }

    private ByteProvider getByteProvider(File file) throws IOException {
        ByteProvider byteProvider;
        Long size = file.length();
        if(size <= 0){
            throw new IOException(String.format("Range of bytes (%d) has to be +ve and non-zero", size));
        }
        if(size <= Integer.MAX_VALUE) {
            byte[] bytes = Files.readAllBytes(Paths.get(file.toURI()));
            byteProvider = new ByteArrayDataProvider(bytes);
        }
        else{
            byteProvider = new FileDataProvider(file);
        }
        return byteProvider;
    }
}